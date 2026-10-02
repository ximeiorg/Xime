// 火山引擎（火山方舟）WebSocket 流式语音识别（TypeScript 脚本插件）
//
// 职责划分：
//   JS    = 全部功能逻辑（连接时机、状态机、prebuffer、bigmodel_async 二进制协议组装/解析、结果上报）
//   宿主  = 仅提供通用原语：
//     host.ws         WebSocket 白名单（connect/sendText/sendBinary/close，事件走回调槽）
//     host.zlib       gzip / gunzip（火山二进制帧强制 gzip）
//     host.bin        uint32be / int32be（帧长度与序号字段）
//     host.asr.emit*  结果回传桥
//     host.config / host.uuid；JSON 用 JS 原生 JSON
//
// 协议细节与帧格式见 libs/protocol.ts。
// TS 范式：host.ws / host.zlib 为 async 服务（await；失败 throw XimeError，try/catch 后 emitError 上报）。

import {
  MSG_FULL_CLIENT_REQ,
  MSG_AUDIO_ONLY,
  MSG_SERVER_RESP,
  MSG_SERVER_ERROR,
  buildFrame,
  parseServerFrame,
  utf8Encode,
} from './libs/protocol';

const WS_URL = 'wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async';
const SAMPLE_RATE = 16000;
const MAX_PENDING_CHUNKS = 300;

const KEY_API_KEY = 'apiKey';
const KEY_APP_KEY = 'appKey';
const KEY_ACCESS_KEY = 'accessKey';
const KEY_RESOURCE_ID = 'resourceId';
const DEFAULT_RESOURCE = 'volc.seedasr.sauc.duration';

interface Session {
  seq: number;
  opening: boolean;
  audioReady: boolean;
  stopping: boolean;
  endSent: boolean;
  pending: Uint8Array[];
  draining: Promise<void> | null;
}

let session: Session | null = null;

function isConfigured(): boolean {
  const apiKey = host.config.get(KEY_API_KEY);
  if (apiKey !== null && apiKey !== undefined && apiKey !== '') return true;
  const appKey = host.config.get(KEY_APP_KEY);
  const accessKey = host.config.get(KEY_ACCESS_KEY);
  return appKey !== null && appKey !== undefined && appKey !== ''
    && accessKey !== null && accessKey !== undefined && accessKey !== '';
}

function getSettingsSchema(): XimeUiNode[] {
  return [
    {
      key: KEY_API_KEY,
      label: 'API Key',
      type: 'secret',
      placeholder: '输入火山方舟 API Key',
      helpText: '在火山引擎方舟平台申请开通流式语音识别后获取',
    },
    {
      key: KEY_APP_KEY,
      label: 'App Key（旧鉴权）',
      type: 'secret',
      required: false,
      placeholder: '旧版 App Key（可选）',
      section: '旧鉴权',
    },
    {
      key: KEY_ACCESS_KEY,
      label: 'Access Key（旧鉴权）',
      type: 'secret',
      required: false,
      placeholder: '旧版 Access Key（可选）',
      section: '旧鉴权',
    },
    {
      key: KEY_RESOURCE_ID,
      label: '模型资源 ID',
      type: 'text',
      defaultValue: DEFAULT_RESOURCE,
      placeholder: DEFAULT_RESOURCE,
      helpText: '默认流式识别 2.0（volc.seedasr.sauc.duration）；1.0 模型填 volc.bigasr.sauc.duration',
    },
  ];
}

async function configure(): Promise<boolean> {
  return true;
}

/** 关闭连接；清理失败保留日志，不覆盖原始识别错误。 */
async function closeSocket(): Promise<void> {
  try {
    await host.ws.close();
  } catch (e) {
    console.error('关闭火山 ASR 连接失败', e);
  }
}

/**
 * 终止当前会话，阻止异步压缩恢复后继续发送旧音频。
 *
 * Args:
 *   current: 发生错误的会话。
 *   message: 需要交给宿主的原始错误信息。
 */
async function failSession(current: Session, message: string): Promise<void> {
  if (session !== current) return;
  session = null;
  current.pending = [];
  host.asr.emitError(message);
  await closeSocket();
}

/**
 * 压缩并发送一帧，仅由初始化流程或唯一的队列消费者调用。
 *
 * Args:
 *   current: 帧所属会话。
 *   type: 火山协议消息类型。
 *   data: 未压缩的请求或 PCM 数据。
 *   last: 是否使用负序号标记最后一包。
 * Returns:
 *   帧发送完成且会话仍有效时为 true。
 */
async function sendFrame(current: Session, type: number, data: Uint8Array, last = false): Promise<boolean> {
  const gz = await host.zlib.gzip(data);
  if (session !== current) return false;
  const sequence = last ? -current.seq : current.seq;
  await host.ws.sendBinary(buildFrame(type, sequence, type === MSG_FULL_CLIENT_REQ ? 0x1 : 0x0, 0x1, gz));
  if (session !== current) return false;
  current.seq++;
  return true;
}

/**
 * 按录制顺序发送缓存及实时音频，最后发送唯一结束包。
 *
 * Args:
 *   current: 由 drainAudio 独占发送权的会话。
 */
async function sendPending(current: Session): Promise<void> {
  try {
    while (session === current && current.pending.length > 0) {
      const pcm = current.pending.shift()!;
      if (!await sendFrame(current, MSG_AUDIO_ONLY, pcm)) return;
    }
    if (session === current && current.stopping && !current.endSent) {
      current.endSent = true;
      await sendFrame(current, MSG_AUDIO_ONLY, new Uint8Array(0), true);
    }
  } catch (e) {
    await failSession(current, (e as Error).message);
  } finally {
    current.draining = null;
  }
}

/**
 * 复用正在运行的消费者，避免 feed/stop 在 await 边界越过旧音频。
 *
 * Args:
 *   current: 需要冲刷的会话；配置包成功前仅保留队列与停止意图。
 * Returns:
 *   当前冲刷任务，或无需发送时的已完成任务。
 */
async function drainAudio(current: Session): Promise<void> {
  if (session !== current || !current.audioReady) return;
  if (current.draining !== null) return current.draining;
  if (current.pending.length === 0 && (!current.stopping || current.endSent)) return;
  current.draining = sendPending(current);
  return current.draining;
}

// ================= 启动 =================

/**
 * 发起连接；音频先进入本会话队列，等待 onOpen 成功发送配置包。
 *
 * Returns:
 *   已发起连接且会话未被取消时为 true。
 */
async function start(): Promise<boolean> {
  if (!isConfigured()) {
    host.asr.emitError('未配置 API Key，请在插件设置中填写');
    return false;
  }
  const taskId = host.uuid();
  const current: Session = {
    seq: 1, opening: false, audioReady: false, stopping: false,
    endSent: false, pending: [], draining: null,
  };
  session = current;

  const headers: Record<string, string> = {};
  const apiKey = host.config.get(KEY_API_KEY);
  if (apiKey !== null && apiKey !== undefined && apiKey !== '') {
    headers['X-Api-Key'] = apiKey;
  } else {
    headers['X-Api-App-Key'] = host.config.get(KEY_APP_KEY) ?? '';
    headers['X-Api-Access-Key'] = host.config.get(KEY_ACCESS_KEY) ?? '';
  }
  headers['X-Api-Resource-Id'] = host.config.get(KEY_RESOURCE_ID) || DEFAULT_RESOURCE;
  headers['X-Api-Request-Id'] = taskId;
  headers['X-Api-Connect-Id'] = host.uuid();
  headers['X-Api-Sequence'] = '-1';

  try {
    await host.ws.connect(WS_URL, headers);
  } catch (e) {
    await failSession(current, (e as Error).message);
    return false;
  }
  return session === current;
}

// ================= WebSocket 事件（状态机） =================

/** 配置成功后立即冲刷首段音频，不等待下一帧或用户停止录音。 */
async function onWsOpen(): Promise<void> {
  const current = session;
  if (current === null || current.opening) return;
  current.opening = true;
  const full = JSON.stringify({
    user: { uid: host.config.get(KEY_APP_KEY) || 'xime' },
    audio: {
      format: 'pcm',
      codec: 'raw',
      rate: SAMPLE_RATE,
      bits: 16,
      channel: 1,
    },
    request: {
      model_name: 'bigmodel',
      enable_itn: true,
      enable_punc: true,
      enable_ddc: false,
      show_utterances: false,
      enable_nonstream: false,
    },
  });
  try {
    if (!await sendFrame(current, MSG_FULL_CLIENT_REQ, utf8Encode(full))) return;
    current.audioReady = true;
    await drainAudio(current);
  } catch (e) {
    await failSession(current, (e as Error).message);
  }
}

/**
 * 处理当前会话的识别结果，忽略解析期间已取消的会话。
 *
 * Args:
 *   frame: 服务端二进制响应。
 */
async function onWsBinary(frame: Uint8Array): Promise<void> {
  const current = session;
  if (current === null) return;
  const parsed = await parseServerFrame(frame);
  if (parsed === null || session !== current) return;

  if (parsed.msgType === MSG_SERVER_RESP) {
    const text = parsed.text || '';
    if (parsed.isLast) {
      session = null;
      current.pending = [];
      host.asr.emitFinal(text);
      await closeSocket();
    } else if (text !== '') {
      host.asr.emitPartial(text);
    }
  } else if (parsed.msgType === MSG_SERVER_ERROR) {
    await failSession(current, 'ASR 错误 ' + String(parsed.code || 0) + ': ' + (parsed.message || ''));
  }
}

/**
 * 连接失败后终止发送。
 *
 * Args:
 *   msg: 宿主传来的连接错误。
 */
function onWsError(msg: string): void {
  if (session !== null) void failSession(session, msg);
}

/** 连接关闭后使所有尚未完成的发送任务失效。 */
function onWsClose(): void {
  if (session !== null) session.pending = [];
  session = null;
}

// ================= 音频数据（主 App 每帧提交，JS 决策） =================

/**
 * 所有音频进入同一有界队列，禁止实时帧越过建连期间的缓存。
 *
 * Args:
 *   pcm: 按录制顺序收到的 PCM 帧。
 */
async function processAudioChunk(pcm: Uint8Array): Promise<void> {
  const current = session;
  if (current === null || current.stopping) return;
  if (current.pending.length >= MAX_PENDING_CHUNKS) {
    await failSession(current, '待发送音频过多，请检查网络后重试');
    return;
  }
  current.pending.push(pcm);
  await drainAudio(current);
}

/** 停止接受新音频；配置与所有已录音频发完后再发送结束包。 */
async function stop(): Promise<void> {
  const current = session;
  if (current === null) return;
  current.stopping = true;
  await drainAudio(current);
}

/** 先使异步任务失效，再关闭连接，取消操作不发送结束包。 */
async function cancel(): Promise<void> {
  onWsClose();
  await closeSocket();
}

const plugin = definePlugin({
  speech: {
    isConfigured,
    configure,
    feed: processAudioChunk,
    start,
    stop,
    cancel,
  },

  settings: {
    schema: getSettingsSchema,
  },

  ws: {
    onOpen: onWsOpen,
    onBinary: onWsBinary,
    onError: onWsError,
    onClose: onWsClose,
  },
});

export default plugin;
