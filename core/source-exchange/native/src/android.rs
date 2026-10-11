//! JNI glue is loaded only when the user starts source exchange.

use crate::transport::Exchange;
use jni::{objects::{GlobalRef, JClass, JObject, JString}, sys::{jboolean, jlong, jstring}, JNIEnv, JavaVM};
use std::{collections::HashMap, path::PathBuf, sync::{atomic::{AtomicI64, AtomicU64, Ordering}, Arc, Mutex, OnceLock}};
use tokio::{runtime::Runtime, sync::{watch, Mutex as AsyncMutex}};

struct AndroidContext { _vm: JavaVM, _application: GlobalRef }
static ANDROID: OnceLock<AndroidContext> = OnceLock::new();
static CONTEXT_INIT: Mutex<()> = Mutex::new(());
static RUNTIME: Mutex<Option<Arc<Runtime>>> = Mutex::new(None);
static OPENING: Mutex<()> = Mutex::new(());
static SESSIONS: OnceLock<Mutex<HashMap<i64, Arc<Session>>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);

struct Session {
    engine: AsyncMutex<Option<Exchange>>,
    cancel: watch::Sender<bool>,
    bytes: AtomicU64,
}

fn runtime() -> anyhow::Result<Arc<Runtime>> {
    let mut slot = RUNTIME.lock().map_err(|_| anyhow::anyhow!("Runtime unavailable"))?;
    if let Some(runtime) = slot.as_ref() { return Ok(runtime.clone()); }
    let created = Arc::new(tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build()?);
    *slot = Some(created.clone());
    Ok(created)
}
fn sessions() -> &'static Mutex<HashMap<i64, Arc<Session>>> { SESSIONS.get_or_init(Mutex::default) }
fn session(handle: i64) -> anyhow::Result<Arc<Session>> {
    sessions().lock().map_err(|_| anyhow::anyhow!("Session registry unavailable"))?.get(&handle).cloned().ok_or_else(|| anyhow::anyhow!("Session closed"))
}
fn text(env: &mut JNIEnv<'_>, value: &JString<'_>) -> anyhow::Result<String> {
    Ok(env.get_string(value)?.into())
}
fn failure(env: &mut JNIEnv<'_>) {
    // Tickets, paths and peer addresses must not appear in uncaught Android errors.
    let _ = env.throw_new("java/lang/IllegalStateException", "Source exchange could not complete this operation");
}

#[no_mangle]
pub extern "system" fn Java_it_lorelore_nyanime_exchange_NativeExchange_initialize(env: JNIEnv, _: JClass, application: JObject) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> anyhow::Result<()> {
        let _guard = CONTEXT_INIT.lock().map_err(|_| anyhow::anyhow!("Context initialization failed"))?;
        if ANDROID.get().is_some() { return Ok(()); }
        // The TLS verifier needs its own JNI class loader, in addition to ndk-context's
        // VM/context used by iroh's Android network watcher. Never bypass platform trust.
        let mut tls_env = unsafe { jni22::EnvUnowned::from_raw(env.get_native_interface().cast()) };
        tls_env.with_env(|next| {
            let context = unsafe { jni22::objects::JObject::from_raw(next, application.as_raw().cast()) };
            rustls_platform_verifier::android::init_with_env(next, context)
        }).resolve::<jni22::errors::ThrowRuntimeExAndDefault>();
        anyhow::ensure!(!env.exception_check()?, "TLS initialization failed");
        let vm = env.get_java_vm()?;
        let reference = env.new_global_ref(application)?;
        let candidate = AndroidContext { _vm: vm, _application: reference };
        // SAFETY: one successful OnceLock winner publishes pointers held for the process lifetime.
        if ANDROID.set(candidate).is_ok() {
            let installed = ANDROID.get().expect("context installed");
            unsafe { ndk_context::initialize_android_context(installed._vm.get_java_vm_pointer().cast(), installed._application.as_obj().as_raw().cast()); }
        }
        Ok(())
    }));
    matches!(result, Ok(Ok(()))) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_it_lorelore_nyanime_exchange_NativeExchange_open(mut env: JNIEnv, _: JClass, directory: JString, sharing: jboolean, internet: jboolean) -> jlong {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> anyhow::Result<i64> {
        anyhow::ensure!(ANDROID.get().is_some(), "Context missing");
        let _opening = OPENING.lock().map_err(|_| anyhow::anyhow!("Session unavailable"))?;
        anyhow::ensure!(sessions().lock().map_err(|_| anyhow::anyhow!("Registry unavailable"))?.len() < 2, "Too many sessions");
        let path = PathBuf::from(text(&mut env, &directory)?);
        let engine = runtime()?.block_on(Exchange::open_scoped(path, sharing != 0, internet != 0))?;
        let (cancel, _) = watch::channel(false);
        let handle = NEXT.fetch_add(1, Ordering::Relaxed);
        sessions().lock().map_err(|_| anyhow::anyhow!("Registry unavailable"))?.insert(handle, Arc::new(Session { engine: AsyncMutex::new(Some(engine)), cancel, bytes: AtomicU64::new(0) }));
        Ok(handle)
    }));
    match result { Ok(Ok(handle)) => handle, _ => { failure(&mut env); 0 } }
}

fn string_result(env: &mut JNIEnv<'_>, result: std::thread::Result<anyhow::Result<String>>) -> jstring {
    match result {
        Ok(Ok(value)) => match env.new_string(value) { Ok(value) => value.into_raw(), Err(_) => { failure(env); std::ptr::null_mut() } },
        _ => { failure(env); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_it_lorelore_nyanime_exchange_NativeExchange_share(mut env: JNIEnv, _: JClass, handle: jlong, file: JString, name: JString) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> anyhow::Result<String> {
        let session = session(handle)?;
        let file = PathBuf::from(text(&mut env, &file)?);
        let name = text(&mut env, &name)?;
        runtime()?.block_on(async {
            let engine = session.engine.lock().await;
            let engine = engine.as_ref().ok_or_else(|| anyhow::anyhow!("Session closed"))?;
            let mut cancel = session.cancel.subscribe();
            anyhow::ensure!(!*cancel.borrow(), "Cancelled");
            tokio::select! {
                _ = cancel.changed() => anyhow::bail!("Cancelled"),
                ticket = engine.share(&file, &name) => ticket,
            }
        })
    }));
    string_result(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_it_lorelore_nyanime_exchange_NativeExchange_receive(mut env: JNIEnv, _: JClass, handle: jlong, ticket: JString, directory: JString) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> anyhow::Result<String> {
        let session = session(handle)?;
        let ticket = text(&mut env, &ticket)?;
        let path = PathBuf::from(text(&mut env, &directory)?);
        runtime()?.block_on(async {
            let engine = session.engine.lock().await;
            let result = engine.as_ref().ok_or_else(|| anyhow::anyhow!("Session closed"))?.receive(&ticket, &path, &mut session.cancel.subscribe(), |bytes| { session.bytes.store(bytes, Ordering::Relaxed); }).await?;
            Ok(serde_json::to_string(&result)?)
        })
    }));
    string_result(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_it_lorelore_nyanime_exchange_NativeExchange_progress(_: JNIEnv, _: JClass, handle: jlong) -> jlong {
    session(handle).map(|session| session.bytes.load(Ordering::Relaxed) as jlong).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_it_lorelore_nyanime_exchange_NativeExchange_cancel(_: JNIEnv, _: JClass, handle: jlong) {
    if let Ok(session) = session(handle) { session.cancel.send_replace(true); }
}

#[no_mangle]
pub extern "system" fn Java_it_lorelore_nyanime_exchange_NativeExchange_close(mut env: JNIEnv, _: JClass, handle: jlong) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> anyhow::Result<()> {
        let session = sessions().lock().map_err(|_| anyhow::anyhow!("Registry unavailable"))?.remove(&handle);
        let shutdown = if let Some(session) = session {
            session.cancel.send_replace(true);
            runtime()?.block_on(async { if let Some(engine) = session.engine.lock().await.take() { engine.shutdown().await?; } Ok::<_, anyhow::Error>(()) })
        } else { Ok(()) };
        // The worker process does not retain networking threads after its last session.
        let _opening = OPENING.lock().map_err(|_| anyhow::anyhow!("Session unavailable"))?;
        if sessions().lock().map_err(|_| anyhow::anyhow!("Registry unavailable"))?.is_empty() {
            if let Some(runtime) = RUNTIME.lock().map_err(|_| anyhow::anyhow!("Runtime unavailable"))?.take() {
                if let Ok(runtime) = Arc::try_unwrap(runtime) { runtime.shutdown_timeout(std::time::Duration::from_secs(2)); }
            }
        }
        shutdown
    }));
    if !matches!(result, Ok(Ok(()))) { failure(&mut env); }
}

#[no_mangle]
pub extern "system" fn Java_it_lorelore_nyanime_exchange_NativeExchange_inspect(mut env: JNIEnv, _: JClass, ticket: JString) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> anyhow::Result<String> {
        let ticket = crate::ticket::ShareTicket::parse(&text(&mut env, &ticket)?)?;
        anyhow::ensure!(ticket.size().is_none_or(|size| size <= crate::transport::MAX_SOURCE_BYTES), "Source too large");
        Ok(serde_json::json!({"name": ticket.label().unwrap_or("source.nyastudio"), "bytes": ticket.size(), "hash": ticket.primary().hash().to_string()}).to_string())
    }));
    string_result(&mut env, result)
}
