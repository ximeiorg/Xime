// funasr-asr 插件测试：识别模型选择（预设 / 自定义）+ run-task 协议组装。
//
// 插件在 speech.start 时经 host.ws 建立连接，ws.onOpen 槽组装 run-task；
// mock 提供 addWs/wsOpen 与 sentWs 记录（见 xime-plugin.d.ts）。

const plugin = (globalThis as any).plugin as Record<string, any>;

const WS_URL = 'wss://dashscope.aliyuncs.com/api-ws/v1/inference/';
const CUSTOM_OPTION = '自定义';

function lastRunTask(): string {
  const sent = __ximeMock.sentWs as Array<{ type: string; text?: string }>;
  const texts = sent
    .filter((m) => m.type === 'text')
    .map((m) => m.text || '')
    .filter((t) => t.indexOf('"action":"run-task"') >= 0);
  return texts.length > 0 ? texts[texts.length - 1] : '';
}

function runTaskModel(): string {
  const m = lastRunTask().match(/"model":"([^"]+)"/);
  return m ? m[1] : '';
}

async function startAndCaptureModel(): Promise<string> {
  __ximeMock.addWs(WS_URL);
  assert.ok(await plugin.speech.start(), 'start 应成功');
  await plugin.ws.onOpen();
  return runTaskModel();
}

test('默认使用 3.1 流式模型', async () => {
  __ximeMock.setConfig('apiKey', 'sk-test');
  assert.equal(await startAndCaptureModel(), 'qwen-audio-3.1-asr-flash-streaming');
});

test('可在预设中选择其它模型', async () => {
  __ximeMock.setConfig('apiKey', 'sk-test');
  __ximeMock.setConfig('model', 'qwen-audio-3.1-asr-flash-message');
  assert.equal(await startAndCaptureModel(), 'qwen-audio-3.1-asr-flash-message');
});

test('选自定义时写入 customModel', async () => {
  __ximeMock.setConfig('apiKey', 'sk-test');
  __ximeMock.setConfig('model', CUSTOM_OPTION);
  __ximeMock.setConfig('customModel', 'my-custom-asr');
  assert.equal(await startAndCaptureModel(), 'my-custom-asr');
});

test('选自定义但未填写模型名时不就绪', () => {
  __ximeMock.setConfig('apiKey', 'sk-test');
  __ximeMock.setConfig('model', CUSTOM_OPTION);
  __ximeMock.setConfig('customModel', '');
  assert.equal(plugin.speech.isConfigured(), false);
});

test('schema 含识别模型与自定义模型字段', () => {
  const keys = (plugin.settings.schema() as any[]).map((f) => f.key);
  assert.ok(keys.indexOf('model') >= 0, '应含 model');
  assert.ok(keys.indexOf('customModel') >= 0, '应含 customModel');
});
