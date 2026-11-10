//! xime-p2p 的 Android JNI 绑定。
//!
//! 只是 `xime-p2p-c` 的 JSON 接口到 Kotlin 的转接：参数与返回值一律走 JSON
//! 字符串，配对、连接、去重等业务全部在 Rust 的 `xime-p2p` 里。
//!
//! JNI 符号名对应 `com.kingzcheung.xime.p2p.P2pNative`，改包名或类名必须同步改这里。

use std::ffi::{CStr, CString, c_char};

use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jint, jstring};

const MISSING_ARGUMENT: &str = r#"{"ok":false,"error":"参数不足"}"#;
const EMPTY_RESULT: &str = r#"{"ok":false,"error":"原生层返回空"}"#;
const BUILD_INFO: &str = r#"{"ok":true,"hasP2p":true,"protocol":"v0.2"}"#;

fn to_c(value: &str) -> CString {
    CString::new(value).unwrap_or_else(|_| CString::new("").expect("空串必定合法"))
}

/// 读取返回的 JSON 并释放原生字符串。
fn take_json(raw: *mut c_char) -> String {
    if raw.is_null() {
        return EMPTY_RESULT.to_string();
    }
    let text = unsafe { CStr::from_ptr(raw) }.to_string_lossy().into_owned();
    unsafe { xime_p2p_c::xime_p2p_free(raw) };
    text
}

fn read_string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    env.get_string(value).ok().map(Into::into)
}

fn out(env: &mut JNIEnv, text: String) -> jstring {
    match env.new_string(text) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn json_result(env: &mut JNIEnv, raw: *mut c_char) -> jstring {
    out(env, take_json(raw))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kingzcheung_xime_p2p_P2pNative_identity<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    dir: JString<'local>,
) -> jstring {
    let Some(dir) = read_string(&mut env, &dir) else {
        return out(&mut env, MISSING_ARGUMENT.to_string());
    };
    let raw = xime_p2p_c::xime_p2p_identity(to_c(&dir).as_ptr());
    json_result(&mut env, raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kingzcheung_xime_p2p_P2pNative_start<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    dir: JString<'local>,
    listen: jboolean,
    discover: jboolean,
    port: jint,
) -> jstring {
    let Some(dir) = read_string(&mut env, &dir) else {
        return out(&mut env, MISSING_ARGUMENT.to_string());
    };
    let raw = xime_p2p_c::xime_p2p_start(
        to_c(&dir).as_ptr(),
        i32::from(listen != 0),
        i32::from(discover != 0),
        port,
    );
    json_result(&mut env, raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kingzcheung_xime_p2p_P2pNative_stop<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jint {
    xime_p2p_c::xime_p2p_stop()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kingzcheung_xime_p2p_P2pNative_setClipboard<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    text: JString<'local>,
    source: JString<'local>,
) -> jint {
    let Some(text) = read_string(&mut env, &text) else {
        return 0;
    };
    let source = read_string(&mut env, &source).unwrap_or_default();
    xime_p2p_c::xime_p2p_set_clipboard(to_c(&text).as_ptr(), to_c(&source).as_ptr())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kingzcheung_xime_p2p_P2pNative_poll<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    max: jint,
) -> jstring {
    let raw = xime_p2p_c::xime_p2p_poll(max);
    json_result(&mut env, raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kingzcheung_xime_p2p_P2pNative_status<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let raw = xime_p2p_c::xime_p2p_status();
    json_result(&mut env, raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kingzcheung_xime_p2p_P2pNative_pairDisplay<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    dir: JString<'local>,
    name: JString<'local>,
    timeout_sec: jint,
) -> jstring {
    let Some(dir) = read_string(&mut env, &dir) else {
        return out(&mut env, MISSING_ARGUMENT.to_string());
    };
    let name = read_string(&mut env, &name).unwrap_or_default();
    let raw = xime_p2p_c::xime_p2p_pair_display(to_c(&dir).as_ptr(), to_c(&name).as_ptr(), timeout_sec);
    json_result(&mut env, raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kingzcheung_xime_p2p_P2pNative_pairInput<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    dir: JString<'local>,
    name: JString<'local>,
    code: JString<'local>,
    addr: JString<'local>,
    timeout_sec: jint,
) -> jstring {
    let Some(dir) = read_string(&mut env, &dir) else {
        return out(&mut env, MISSING_ARGUMENT.to_string());
    };
    let name = read_string(&mut env, &name).unwrap_or_default();
    let Some(code) = read_string(&mut env, &code) else {
        return out(&mut env, MISSING_ARGUMENT.to_string());
    };
    let addr = read_string(&mut env, &addr).unwrap_or_default();
    let addr = to_c(&addr);

    let raw = xime_p2p_c::xime_p2p_pair_input(
        to_c(&dir).as_ptr(),
        to_c(&name).as_ptr(),
        to_c(&code).as_ptr(),
        if addr.as_bytes().is_empty() {
            std::ptr::null()
        } else {
            addr.as_ptr()
        },
        timeout_sec,
    );
    json_result(&mut env, raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kingzcheung_xime_p2p_P2pNative_pairPoll<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let raw = xime_p2p_c::xime_p2p_pair_poll();
    json_result(&mut env, raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kingzcheung_xime_p2p_P2pNative_pairCancel<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jint {
    xime_p2p_c::xime_p2p_pair_cancel()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kingzcheung_xime_p2p_P2pNative_peers<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    dir: JString<'local>,
) -> jstring {
    let Some(dir) = read_string(&mut env, &dir) else {
        return out(&mut env, MISSING_ARGUMENT.to_string());
    };
    let raw = xime_p2p_c::xime_p2p_peers(to_c(&dir).as_ptr());
    json_result(&mut env, raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kingzcheung_xime_p2p_P2pNative_forget<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    dir: JString<'local>,
    did: JString<'local>,
) -> jint {
    let (Some(dir), Some(did)) = (read_string(&mut env, &dir), read_string(&mut env, &did)) else {
        return 0;
    };
    xime_p2p_c::xime_p2p_forget(to_c(&dir).as_ptr(), to_c(&did).as_ptr())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kingzcheung_xime_p2p_P2pNative_buildInfo<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    out(&mut env, BUILD_INFO.to_string())
}
