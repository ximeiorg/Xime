// 火山 ASR 回归：连接期间的 PCM 必须先于实时音频发送，结束和取消不得穿透会话。
// 测试使用官方 QuickJS mock，不调用真实网络或云端识别服务。

const plugin = (globalThis as any).plugin as Record<string, any>;
const WS_URL = 'wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async';
const PCM_A = new Uint8Array([1, 0]);
const PCM_B = new Uint8Array([2, 0]);
const PCM_C = new Uint8Array([3, 0]);
const MAX_MICROTASK_TURNS = 100;

interface TestSession {
  frames: Uint8Array[];
  attempts: Uint8Array[];
  closeCount: number;
  gzip: typeof host.zlib.gzip;
  sendBinary: typeof host.ws.sendBinary;
}

interface AsyncGate {
  entered: boolean;
  wait: Promise<void>;
  release: () => void;
}

/**
 * 创建可手动释放的异步边界，不依赖墙上时钟。
 *
 * Returns:
 *   可供 gzip stub 暂停和恢复的门闩。
 */
function createGate(): AsyncGate {
  let release: () => void = () => {};
  const wait = new Promise<void>((resolve) => { release = resolve; });
  return { entered: false, wait, release };
}

/**
 * 暂停首个匹配的压缩请求，复现发送流程中的 await 竞争。
 *
 * Args:
 *   session: 提供未覆写的 gzip 原语。
 *   matches: 需要暂停的请求谓词。
 * Returns:
 *   暂停请求后可手动释放的门闩。
 */
function pauseCompression(session: TestSession, matches: (data: Uint8Array) => boolean): AsyncGate {
  const gate = createGate();
  host.zlib.gzip = async (data: Uint8Array) => {
    if (!gate.entered && matches(data)) {
      gate.entered = true;
      await gate.wait;
    }
    return session.gzip(data);
  };
  return gate;
}

/**
 * 在有限微任务轮次内等待 stub 被调用，缺少调用时快速失败。
 *
 * Args:
 *   gate: 待观察的异步门闩。
 *   message: 未进入门闩时的断言说明。
 */
async function waitForGate(gate: AsyncGate, message: string): Promise<void> {
  for (let turn = 0; turn < MAX_MICROTASK_TURNS && !gate.entered; turn++) {
    await Promise.resolve();
  }
  assert.ok(gate.entered, message);
}

/**
 * 为每个用例重置插件会话和 mock，并在退出时恢复所有覆写方法。
 *
 * Args:
 *   body: 接收真实发送记录和宿主原语的测试函数。
 */
async function withSession(body: (session: TestSession) => Promise<void>): Promise<void> {
  const originalGzip = host.zlib.gzip;
  const originalSend = host.ws.sendBinary;
  const originalClose = host.ws.close;
  await plugin.speech.cancel();
  plugin.ws.onClose();
  __ximeMock.setConfig('apiKey', 'test-volc-key');
  __ximeMock.setConfig('appKey', '');
  __ximeMock.setConfig('accessKey', '');
  __ximeMock.setConfig('resourceId', 'volc.seedasr.sauc.duration');
  __ximeMock.asrEvents.splice(0);
  __ximeMock.addWs(WS_URL);

  const session: TestSession = {
    frames: [], attempts: [], closeCount: 0,
    gzip: originalGzip, sendBinary: originalSend,
  };
  host.ws.sendBinary = async (frame: Uint8Array) => {
    const copy = new Uint8Array(frame);
    session.attempts.push(copy);
    const result = await originalSend(frame);
    session.frames.push(copy);
    return result;
  };
  session.sendBinary = host.ws.sendBinary;
  host.ws.close = async () => {
    session.closeCount++;
    await originalClose();
  };
  try {
    assert.ok(await plugin.speech.start(), '配置后的会话应能发起连接');
    await body(session);
  } finally {
    host.zlib.gzip = originalGzip;
    host.ws.sendBinary = session.sendBinary;
    try {
      await plugin.speech.cancel();
      plugin.ws.onClose();
    } finally {
      host.ws.sendBinary = originalSend;
      host.ws.close = originalClose;
    }
  }
}

/**
 * 解压成功发送的协议帧，按用户可观察的音频顺序命名。
 *
 * Args:
 *   frames: 已成功交给宿主的二进制帧。
 * Returns:
 *   INIT、A、B、C 或 END 组成的发送顺序。
 */
async function frameOrder(frames: Uint8Array[]): Promise<string[]> {
  const order: string[] = [];
  for (const frame of frames) {
    if ((frame[1] >> 4) === 1) {
      order.push('INIT');
    } else if ((frame[1] & 0x0f) === 3) {
      order.push('END');
    } else {
      const payload = await host.zlib.gunzip(new Uint8Array(frame.subarray(12)));
      assert.equal(payload.length, 2, 'PCM 帧必须保留完整原始样本');
      assert.equal(payload[1], 0, 'PCM 样本第二字节不得改变');
      order.push(['', 'A', 'B', 'C'][payload[0]] || 'UNKNOWN');
    }
  }
  return order;
}

/**
 * 读取协议帧的有符号大端序号。
 *
 * Args:
 *   frames: 待核验的协议帧。
 * Returns:
 *   按发送顺序排列的序号。
 */
function frameSequences(frames: Uint8Array[]): number[] {
  return frames.map((frame) =>
    (frame[4] << 24) | (frame[5] << 16) | (frame[6] << 8) | frame[7]);
}

test('延迟连接完成后立即发送预缓冲，无需等待结束', async () => {
  await withSession(async (session) => {
    await plugin.speech.feed(PCM_A);
    await plugin.speech.feed(PCM_B);
    assert.equal(session.frames.length, 0, '连接前不得发音频');
    await plugin.ws.onOpen();
    assert.deepEqual(await frameOrder(session.frames), ['INIT', 'A', 'B'],
      '连接完成后应立即按录制顺序冲刷开头音频');
    assert.deepEqual(frameSequences(session.frames), [1, 2, 3]);
    await plugin.speech.stop();
    assert.deepEqual(await frameOrder(session.frames), ['INIT', 'A', 'B', 'END']);
    assert.deepEqual(frameSequences(session.frames), [1, 2, 3, -4]);
  });
});

test('冲刷期间追加实时音频保持 FIFO 和唯一序号', async () => {
  await withSession(async (session) => {
    await plugin.speech.feed(PCM_A);
    await plugin.speech.feed(PCM_B);
    const gate = createGate();
    host.zlib.gzip = async (data: Uint8Array) => {
      if (data.length === PCM_A.length && data[0] === PCM_A[0]) {
        gate.entered = true;
        await gate.wait;
      }
      return session.gzip(data);
    };
    const opening = plugin.ws.onOpen();
    let feeding: Promise<unknown> | undefined;
    try {
      await waitForGate(gate, '连接完成后必须开始冲刷 A');
      feeding = plugin.speech.feed(PCM_C);
      await Promise.resolve();
      gate.release();
      await Promise.all([opening, feeding]);
      await plugin.speech.stop();
      assert.deepEqual(await frameOrder(session.frames), ['INIT', 'A', 'B', 'C', 'END']);
      assert.deepEqual(frameSequences(session.frames), [1, 2, 3, 4, -5],
        '跨 await 的并发输入不得重复或颠倒序号');
    } finally {
      gate.release();
      await Promise.all([opening, feeding]);
    }
  });
});

test('连接前结束先保留意图，配置与音频必须先于唯一结束包', async () => {
  await withSession(async (session) => {
    await plugin.speech.feed(PCM_A);
    await plugin.speech.feed(PCM_B);
    await plugin.speech.stop();
    assert.equal(session.attempts.length, 0, 'open 前不得提前发音频或结束包');
    await plugin.ws.onOpen();
    assert.deepEqual(await frameOrder(session.frames), ['INIT', 'A', 'B', 'END']);
    assert.deepEqual(frameSequences(session.frames), [1, 2, 3, -4]);
  });
});

test('重复结束只发一个结束包，并拒绝结束后的音频', async () => {
  await withSession(async (session) => {
    await plugin.ws.onOpen();
    await plugin.speech.feed(PCM_A);
    await Promise.all([plugin.speech.stop(), plugin.speech.stop()]);
    await plugin.speech.feed(PCM_B);
    await plugin.speech.stop();
    assert.deepEqual(await frameOrder(session.frames), ['INIT', 'A', 'END']);
    assert.deepEqual(frameSequences(session.frames), [1, 2, -3]);
  });
});

test('异步压缩期间取消，旧音频不能穿透到新会话', async () => {
  await withSession(async (session) => {
    await plugin.ws.onOpen();
    const gate = createGate();
    host.zlib.gzip = async (data: Uint8Array) => {
      if (data.length === PCM_A.length && data[0] === PCM_A[0]) {
        gate.entered = true;
        await gate.wait;
      }
      return session.gzip(data);
    };
    const feeding = plugin.speech.feed(PCM_A);
    try {
      await waitForGate(gate, '测试应暂停在 PCM 压缩边界');
      await plugin.speech.cancel();
      __ximeMock.addWs(WS_URL);
      assert.ok(await plugin.speech.start(), '取消后应能建立新会话');
      await plugin.ws.onOpen();
      const attemptsBeforeResume = session.attempts.length;
      gate.release();
      await feeding;
      assert.equal(session.attempts.length, attemptsBeforeResume, '取消后的旧 PCM 不得尝试发送');
      assert.deepEqual(await frameOrder(session.frames), ['INIT', 'INIT']);
      assert.deepEqual(frameSequences(session.frames), [1, 1]);
    } finally {
      gate.release();
      await feeding;
    }
  });
});

test('配置包压缩失败后终止，不再发送缓冲或成功结束包', async () => {
  await withSession(async (session) => {
    await plugin.speech.feed(PCM_A);
    host.zlib.gzip = async (data: Uint8Array) => {
      if (data[0] === 0x7b) throw new Error('injected init gzip failure');
      return session.gzip(data);
    };
    await plugin.ws.onOpen();
    await plugin.speech.feed(PCM_B);
    await plugin.speech.stop();
    assert.ok(__ximeMock.asrEvents.some((event) => event.type === 'error'), '失败必须上报');
    assert.ok(session.closeCount > 0, '配置失败必须关闭会话');
    assert.equal(session.attempts.length, 0, '配置未成功不得发送任何音频或结束包');
  });
});

test('配置包发送失败后终止，不把失败当作音频就绪', async () => {
  await withSession(async (session) => {
    host.ws.sendBinary = async (frame: Uint8Array) => {
      if ((frame[1] >> 4) === 1) throw new Error('injected init send failure');
      return session.sendBinary(frame);
    };
    await plugin.ws.onOpen();
    await plugin.speech.feed(PCM_A);
    await plugin.speech.stop();
    assert.ok(__ximeMock.asrEvents.some((event) => event.type === 'error'), '发送失败必须上报');
    assert.ok(session.closeCount > 0, '配置发送失败必须关闭会话');
    assert.deepEqual(await frameOrder(session.frames), [], '配置失败后不得发音频或成功结束包');
  });
});

test('音频发送失败后终止，不续发其它音频或成功结束包', async () => {
  await withSession(async (session) => {
    await plugin.ws.onOpen();
    let audioAttempts = 0;
    host.ws.sendBinary = async (frame: Uint8Array) => {
      if ((frame[1] >> 4) === 2 && (frame[1] & 0x0f) !== 3) {
        audioAttempts++;
        throw new Error('injected audio send failure');
      }
      return session.sendBinary(frame);
    };
    await plugin.speech.feed(PCM_A);
    await plugin.speech.feed(PCM_B);
    await plugin.speech.stop();
    assert.ok(__ximeMock.asrEvents.some((event) => event.type === 'error'), '发送失败必须上报');
    assert.ok(session.closeCount > 0, '音频发送失败必须关闭会话');
    assert.equal(audioAttempts, 1, '首次发送失败后不得再尝试发送其它音频');
    assert.deepEqual(await frameOrder(session.frames), ['INIT'], '发送失败后不得续发成功结束包');
  });
});

test('PCM 压缩期间停止，先发在途音频再发唯一结束包', async () => {
  await withSession(async (session) => {
    await plugin.ws.onOpen();
    const gate = pauseCompression(session, (data) => data.length === 2 && data[0] === PCM_A[0]);
    const feeding = plugin.speech.feed(PCM_A);
    let stopping: Promise<unknown> | undefined;
    try {
      await waitForGate(gate, 'PCM 应停在异步压缩边界');
      stopping = plugin.speech.stop();
      await plugin.speech.feed(PCM_B);
      assert.deepEqual(await frameOrder(session.frames), ['INIT'], '结束包不得越过在途 PCM');
      gate.release();
      await Promise.all([feeding, stopping]);
      assert.deepEqual(await frameOrder(session.frames), ['INIT', 'A', 'END']);
      assert.deepEqual(frameSequences(session.frames), [1, 2, -3]);
    } finally {
      gate.release();
      await Promise.all([feeding, stopping]);
    }
  });
});

test('配置包压缩期间取消，不发送配置、音频或结束包', async () => {
  await withSession(async (session) => {
    await plugin.speech.feed(PCM_A);
    const gate = pauseCompression(session, (data) => data[0] === 0x7b);
    const opening = plugin.ws.onOpen();
    try {
      await waitForGate(gate, '配置包应停在异步压缩边界');
      await plugin.speech.cancel();
      gate.release();
      await opening;
      await plugin.speech.feed(PCM_B);
      await plugin.speech.stop();
      assert.equal(session.attempts.length, 0, '取消应使在途配置及缓存音频失效');
      assert.ok(session.closeCount > 0, '取消应关闭连接');
    } finally {
      gate.release();
      await opening;
    }
  });
});

test('重复连接完成回调不重复发配置包', async () => {
  await withSession(async (session) => {
    const gate = pauseCompression(session, (data) => data[0] === 0x7b);
    const opening = plugin.ws.onOpen();
    try {
      await waitForGate(gate, '首个连接回调应开始配置包压缩');
      await plugin.ws.onOpen();
      gate.release();
      await opening;
      await plugin.ws.onOpen();
      await plugin.speech.feed(PCM_A);
      await plugin.speech.stop();
      assert.deepEqual(await frameOrder(session.frames), ['INIT', 'A', 'END']);
      assert.deepEqual(frameSequences(session.frames), [1, 2, -3]);
    } finally {
      gate.release();
      await opening;
    }
  });
});

test('无音频且连接前停止，只发送配置和一个空结束包', async () => {
  await withSession(async (session) => {
    await plugin.speech.stop();
    await plugin.speech.stop();
    assert.equal(session.attempts.length, 0, '未连接时应只记录停止意图');
    await plugin.ws.onOpen();
    await plugin.speech.stop();
    assert.deepEqual(await frameOrder(session.frames), ['INIT', 'END']);
    assert.deepEqual(frameSequences(session.frames), [1, -2]);
    const tail = session.frames[session.frames.length - 1];
    const payload = await host.zlib.gunzip(new Uint8Array(tail.subarray(12)));
    assert.equal(payload.length, 0, '无音频会话的结束包必须为空');
  });
});

test('达到 300 帧缓存上限后明确报错，禁止默默丢弃最早音频', async () => {
  await withSession(async (session) => {
    for (let index = 0; index < 300; index++) {
      await plugin.speech.feed(PCM_A);
    }
    assert.equal(__ximeMock.asrEvents.length, 0, '上限内的缓存不得报错');
    assert.equal(session.attempts.length, 0, '未连接时不得提前发送缓存');
    await plugin.speech.feed(PCM_B);
    const errors = __ximeMock.asrEvents.filter((event) => event.type === 'error');
    assert.equal(errors.length, 1, '超出上限必须明确报错，不能静默截掉开头');
    assert.ok((errors[0].message || '').trim().length > 0, '错误必须包含可读说明');
    assert.ok(session.closeCount > 0, '溢出后应终止会话');
    await plugin.ws.onOpen();
    await plugin.speech.feed(PCM_C);
    await plugin.speech.stop();
    assert.equal(session.attempts.length, 0, '溢出失败后不得继续发送或伪装成功结束');
  });
});

test('连接阶段或就绪后发生错误，后续 feed 和 stop 均不发送', async () => {
  for (const opened of [false, true]) {
    await withSession(async (session) => {
      if (opened) await plugin.ws.onOpen();
      await plugin.speech.feed(PCM_A);
      const attemptsBeforeError = session.attempts.length;
      plugin.ws.onError('injected connection failure');
      await Promise.resolve();
      await plugin.speech.feed(PCM_B);
      await plugin.speech.stop();
      await plugin.ws.onOpen();
      assert.equal(session.attempts.length, attemptsBeforeError,
        '连接错误后不得发送缓存、新音频或结束包');
      const errors = __ximeMock.asrEvents.filter((event) => event.type === 'error');
      assert.equal(errors.length, 1, '连接错误应只上报一次');
      assert.equal(errors[0].message, 'injected connection failure');
      assert.ok(session.closeCount > 0, '连接错误应关闭会话');
    });
  }
});
