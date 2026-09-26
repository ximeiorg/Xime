// 阿里百炼 FunAsr 在线语音识别（TypeScript 脚本插件）
//
// 职责划分：
//   JS    = 全部功能逻辑（连接时机、状态机、prebuffer、dashscope 协议组装/解析、结果上报）
//   宿主  = 仅提供通用原语：
//     host.ws         通用 WebSocket 白名单（connect/sendText/sendBinary/close，事件走回调槽）
//     host.asr.emit*  结果回传桥（final/partial/error/state）
//     host.config / host.uuid；JSON 用 JS 原生 JSON
//   主 App 只把 PCM 数据通过 processAudioChunk 提交给 JS，由 JS 决定缓冲还是发送
//
// 沙箱约束：无 URL/Intl；JSON.parse 非法输入抛异常，须 try/catch。
// TS 范式：host.ws 为 async 服务（await；失败 throw XimeError，try/catch 后 emitError 上报）。

const WS_URL = 'wss://dashscope.aliyuncs.com/api-ws/v1/inference/';
const SAMPLE_RATE = 16000;
const FORMAT = 'pcm';
const KEY_API_KEY = 'apiKey';
const KEY_MODEL = 'model';
const KEY_CUSTOM_MODEL = 'customModel';

// 预设模型（百炼实时语音识别 WebSocket run-task 协议，与旧版 3.0 协议一致）：
//   3.1 系列识别效果与价格均优于 3.0；streaming 为实时流式，message 为同类新模型。
const MODEL_PRESETS = [
  'qwen-audio-3.1-asr-flash-streaming',
  'qwen-audio-3.1-asr-flash-message',
  'qwen-audio-3.0-asr-flash-streaming',
];
const DEFAULT_MODEL = MODEL_PRESETS[0];
// 下拉中的“自定义”哨兵值：选中后改用 customModel 字段填写的模型名
const CUSTOM_MODEL = '自定义';

let taskId = '';
let audioReady = false;
let prebuffer: Uint8Array[] = [];

// ================= 元信息 =================

function isConfigured(): boolean {
  const v = host.config.get(KEY_API_KEY);
  if (v === null || v === undefined || v === '') return false;
  // 已选“自定义”但未填写模型名 → 视为未就绪，避免发出空 model 请求
  if ((host.config.get(KEY_MODEL) || '') === CUSTOM_MODEL) {
    return (host.config.get(KEY_CUSTOM_MODEL) || '').trim() !== '';
  }
  return true;
}

/** 解析当前生效模型：预设直用；选“自定义”时取 customModel；空/非法回退默认。 */
function currentModel(): string {
  const selected = host.config.get(KEY_MODEL) || '';
  if (selected === CUSTOM_MODEL) {
    const custom = (host.config.get(KEY_CUSTOM_MODEL) || '').trim();
    return custom !== '' ? custom : DEFAULT_MODEL;
  }
  return MODEL_PRESETS.indexOf(selected) >= 0 ? selected : DEFAULT_MODEL;
}

function getSettingsSchema(): XimeUiNode[] {
  return [
    {
      key: KEY_API_KEY,
      label: 'API Key',
      type: 'secret',
      placeholder: '输入阿里百炼 API Key',
      helpText: '访问阿里云百炼平台获取 API Key',
    },
    {
      key: KEY_MODEL,
      label: '识别模型',
      type: 'select',
      defaultValue: DEFAULT_MODEL,
      options: MODEL_PRESETS.concat([CUSTOM_MODEL]),
      helpText: '推荐 3.1 系列（比 3.0 更好用、更便宜）；选“自定义”后在下一项填写模型名',
    },
    {
      key: KEY_CUSTOM_MODEL,
      label: '自定义模型名',
      type: 'text',
      placeholder: '如 qwen-audio-3.1-asr-flash-streaming',
      helpText: '仅“识别模型”选“自定义”时生效；填写百炼实时语音识别模型名',
    },
  ];
}

async function configure(): Promise<boolean> {
  return true;
}

// ================= 启动 =================

async function start(): Promise<boolean> {
  const apiKey = host.config.get(KEY_API_KEY);
  if (apiKey === null || apiKey === undefined || apiKey === '') {
    host.asr.emitError('未配置 API Key，请在插件设置中填写');
    return false;
  }
  taskId = host.uuid();
  audioReady = false;
  prebuffer = [];
  try {
    await host.ws.connect(WS_URL, { Authorization: 'Bearer ' + apiKey });
  } catch (e) {
    host.asr.emitError((e as Error).message);
    return false;
  }
  return true;
}

// ================= WebSocket 事件（状态机） =================

async function onWsOpen(): Promise<void> {
  await sendRunTask();
}

async function onWsMessage(text: string): Promise<void> {
  let msg: Record<string, any> | null = null;
  try {
    msg = JSON.parse(text);
  } catch (e) {
    return;
  }
  if (msg === null || msg === undefined || msg.header === null || msg.header === undefined) return;
  const header = msg.header;
  const event = header.event;

  if (event === 'task-started') {
    // 任务就绪：开始直发音频，并冲刷连接建立前缓冲的音频
    audioReady = true;
    await flushPrebuffer();
  } else if (event === 'result-generated') {
    const output = msg.payload ? msg.payload.output : null;
    if (output === null || output === undefined) return;
    const sentence = output.sentence;
    if (sentence === null || sentence === undefined || sentence.heartbeat) return;
    const resultText = sentence.text || '';
    if (resultText !== '') {
      if (sentence.sentence_end) {
        host.asr.emitFinal(resultText);
      } else {
        host.asr.emitPartial(resultText);
      }
    }
  } else if (event === 'task-finished') {
    await host.ws.close();
  } else if (event === 'task-failed') {
    const code = header.error_code || 'UNKNOWN';
    const reason = header.error_message || 'Unknown error';
    host.asr.emitError('识别失败 [' + code + ']: ' + reason);
    await host.ws.close();
  }
}

function onWsError(msg: string): void {
  host.asr.emitError(msg);
}

function onWsClose(): void {
  taskId = '';
  audioReady = false;
  prebuffer = [];
}

// ================= 音频数据（主 App 每帧提交，JS 决策） =================

async function processAudioChunk(pcm: Uint8Array): Promise<void> {
  if (audioReady) {
    try {
      await host.ws.sendBinary(pcm);
    } catch (e) {
      host.asr.emitError((e as Error).message);
    }
  } else {
    prebuffer.push(pcm);
    if (prebuffer.length > 300) prebuffer.shift();
  }
}

async function flushPrebuffer(): Promise<void> {
  for (const frame of prebuffer) {
    try {
      await host.ws.sendBinary(frame);
    } catch (e) {
      host.asr.emitError((e as Error).message);
    }
  }
  prebuffer = [];
}

// ================= dashscope 协议 =================

async function sendRunTask(): Promise<void> {
  if (taskId === '') return;
  try {
    await host.ws.sendText(JSON.stringify({
      header: {
        action: 'run-task',
        task_id: taskId,
        streaming: 'duplex',
      },
      payload: {
        task_group: 'audio',
        task: 'asr',
        'function': 'recognition',
        model: currentModel(),
        parameters: {
          format: FORMAT,
          sample_rate: SAMPLE_RATE,
        },
        input: {},
      },
    }));
  } catch (e) {
    host.asr.emitError((e as Error).message);
  }
}

async function stop(): Promise<void> {
  if (taskId !== '') {
    try {
      await host.ws.sendText(JSON.stringify({
        header: {
          action: 'finish-task',
          task_id: taskId,
          streaming: 'duplex',
        },
        payload: { input: {} },
      }));
    } catch (e) {
      host.asr.emitError((e as Error).message);
    }
  }
}

async function cancel(): Promise<void> {
  await host.ws.close();
  taskId = '';
  audioReady = false;
  prebuffer = [];
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
    onMessage: onWsMessage,
    onError: onWsError,
    onClose: onWsClose,
  },
});

export default plugin;
