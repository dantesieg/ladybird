/*
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "ALooperEventLoopImplementation.h"
#include "JNIHelpers.h"
#include "LadybirdHub.h"
#include <AK/ByteString.h>
#include <AK/Format.h>
#include <AK/OwnPtr.h>
#include <LibCore/Directory.h>
#include <LibCore/EventLoop.h>
#include <LibCore/Resource.h>
#include <LibCore/ResourceImplementationFile.h>
#include <LibWebView/Utilities.h>
#include <jni.h>
#include <stdlib.h>

JavaVM* global_vm;
static OwnPtr<Core::EventLoop> s_main_event_loop;
static RefPtr<Ladybird::HubClient> s_hub;
static jobject s_java_instance;
static jmethodID s_schedule_event_loop_method;

static ByteString byte_string_from_jstring(JNIEnv* env, jstring string)
{
    char const* raw = env->GetStringUTFChars(string, nullptr);
    ByteString result { raw };
    env->ReleaseStringUTFChars(string, raw);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_LadybirdActivity_initNativeCode(JNIEnv* env, jobject thiz, jstring resource_dir, jstring tag_name, jobject timer_service, jstring user_dir, jstring native_library_dir)
{
    // The asset bundle unpacks Base/res into a "res" directory of the app's files.
    auto resource_root = ByteString::formatted("{}/res", byte_string_from_jstring(env, resource_dir));
    auto helper_directory = byte_string_from_jstring(env, native_library_dir);
    // NB: The external files directory that Java hands us is on a FUSE file system, where SELinux forbids the Unix
    //     sockets that the browser listens on, so everything lives in the app's internal storage instead.
    (void)user_dir;
    auto user_directory = ByteString::formatted("{}/user", byte_string_from_jstring(env, resource_dir));
    for (auto name : { "config"sv, "userdata"sv, "cache"sv, "run"sv, "tmp"sv })
        (void)Core::Directory::create(ByteString::formatted("{}/{}", user_directory, name), Core::Directory::CreateDirectories::Yes);

    AK::set_log_tag_name(byte_string_from_jstring(env, tag_name).characters());

    // Every process the UI starts inherits these, so the helpers find each other, their resources, and the libraries
    // they link against, all of which the APK unpacks into the app's own directories.
    setenv("LADYBIRD_RESOURCE_ROOT", resource_root.characters(), 1);
    setenv("LADYBIRD_HELPER_DIRECTORY", helper_directory.characters(), 1);
    setenv("LD_LIBRARY_PATH", helper_directory.characters(), 1);
    setenv("HOME", user_directory.characters(), 1);
    // Android keeps its root certificates under hashed names that OpenSSL does not use, so the app gathers them into
    // one bundle when it first starts.
    setenv("SSL_CERT_FILE", ByteString::formatted("{}/cacert.pem", byte_string_from_jstring(env, resource_dir)).characters(), 1);
    setenv("XDG_CONFIG_HOME", ByteString::formatted("{}/config", user_directory).characters(), 1);
    setenv("XDG_DATA_HOME", ByteString::formatted("{}/userdata", user_directory).characters(), 1);
    setenv("XDG_CACHE_HOME", ByteString::formatted("{}/cache", user_directory).characters(), 1);
    setenv("XDG_RUNTIME_DIR", ByteString::formatted("{}/run", user_directory).characters(), 1);
    setenv("TMPDIR", ByteString::formatted("{}/tmp", user_directory).characters(), 1);

    WebView::s_ladybird_resource_root = resource_root;
    Core::ResourceImplementation::install(make<Core::ResourceImplementationFile>(MUST(String::from_byte_string(resource_root))));

    env->GetJavaVM(&global_vm);
    VERIFY(global_vm);
    s_java_instance = env->NewGlobalRef(thiz);
    jclass clazz = env->GetObjectClass(s_java_instance);
    s_schedule_event_loop_method = env->GetMethodID(clazz, "scheduleEventLoop", "()V");
    VERIFY(s_schedule_event_loop_method);
    env->DeleteLocalRef(clazz);

    auto* event_loop_manager = new Ladybird::ALooperEventLoopManager(env->NewGlobalRef(timer_service));
    event_loop_manager->on_did_post_event = [] {
        Ladybird::JavaEnvironment env(global_vm);
        env.get()->CallVoidMethod(s_java_instance, s_schedule_event_loop_method);
    };
    Core::EventLoopManager::install(*event_loop_manager);
    s_main_event_loop = make<Core::EventLoop>();

    auto hub_or_error = Ladybird::HubClient::launch(helper_directory);
    if (hub_or_error.is_error()) {
        dbgln("Unable to start the Hub: {}", hub_or_error.error());
        return;
    }
    s_hub = hub_or_error.release_value();
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_LadybirdActivity_execMainEventLoop(JNIEnv*, jobject)
{
    if (s_main_event_loop)
        s_main_event_loop->pump(Core::EventLoop::WaitMode::PollForEvents);
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_LadybirdActivity_disposeNativeCode(JNIEnv* env, jobject)
{
    s_hub = nullptr;
    s_main_event_loop = nullptr;
    s_schedule_event_loop_method = nullptr;
    env->DeleteGlobalRef(s_java_instance);
    delete &Core::EventLoopManager::the();
}

// The WebView's native half.

using Ladybird::AndroidView;

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_WebViewImplementation_00024Companion_nativeClassInit(JNIEnv* env, jobject)
{
    auto local_class = env->FindClass("org/serenityos/ladybird/WebViewImplementation");
    VERIFY(local_class);
    AndroidView::s_class = reinterpret_cast<jclass>(env->NewGlobalRef(local_class));
    env->DeleteLocalRef(local_class);

    AndroidView::s_invalidate_layout_method = env->GetMethodID(AndroidView::s_class, "invalidateLayout", "()V");
    VERIFY(AndroidView::s_invalidate_layout_method);
    AndroidView::s_on_load_start_method = env->GetMethodID(AndroidView::s_class, "onLoadStart", "(Ljava/lang/String;Z)V");
    VERIFY(AndroidView::s_on_load_start_method);
    AndroidView::s_on_input_method_state_changed_method = env->GetMethodID(AndroidView::s_class, "onInputMethodStateChanged", "(Z)V");
    VERIFY(AndroidView::s_on_input_method_state_changed_method);
    AndroidView::s_on_state_changed_method = env->GetMethodID(AndroidView::s_class, "onStateChanged", "(Ljava/lang/String;Ljava/lang/String;ZZZ)V");
    VERIFY(AndroidView::s_on_state_changed_method);
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_serenityos_ladybird_WebViewImplementation_nativeObjectInit(JNIEnv* env, jobject thiz, jboolean is_private, jboolean websites_prefer_dark)
{
    auto view_or_error = AndroidView::create(env->NewGlobalRef(thiz), 1.0, is_private, websites_prefer_dark);
    if (view_or_error.is_error()) {
        dbgln("Unable to create a view: {}", view_or_error.error());
        return 0;
    }
    return reinterpret_cast<jlong>(view_or_error.release_value().leak_ptr());
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_WebViewImplementation_nativeObjectDispose(JNIEnv* env, jobject, jlong instance)
{
    auto* view = reinterpret_cast<AndroidView*>(instance);
    if (!view)
        return;
    env->DeleteGlobalRef(view->java_instance());
    delete view;
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_WebViewImplementation_nativeDrawIntoBitmap(JNIEnv* env, jobject, jlong instance, jobject bitmap)
{
    if (auto* view = reinterpret_cast<AndroidView*>(instance))
        view->draw_into(env, bitmap);
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_WebViewImplementation_nativeSetViewportGeometry(JNIEnv*, jobject, jlong instance, jint width, jint height)
{
    if (auto* view = reinterpret_cast<AndroidView*>(instance))
        view->set_viewport(width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_WebViewImplementation_nativeSetDevicePixelRatio(JNIEnv*, jobject, jlong instance, jfloat ratio)
{
    if (auto* view = reinterpret_cast<AndroidView*>(instance))
        view->set_device_pixel_ratio(ratio);
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_WebViewImplementation_nativeLoadURL(JNIEnv* env, jobject, jlong instance, jstring url)
{
    if (auto* view = reinterpret_cast<AndroidView*>(instance))
        view->load(byte_string_from_jstring(env, url));
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_WebViewImplementation_nativeMouseEvent(JNIEnv*, jobject, jlong instance, jint event_type, jfloat x, jfloat y, jfloat, jfloat)
{
    if (auto* view = reinterpret_cast<AndroidView*>(instance))
        view->touch(event_type, x, y);
}

#define VIEW_METHOD(name, call)                                                                           \
    extern "C" JNIEXPORT void JNICALL Java_org_serenityos_ladybird_WebViewImplementation_##name(JNIEnv*, jobject, jlong instance) \
    {                                                                                                     \
        if (auto* view = reinterpret_cast<AndroidView*>(instance))                                        \
            view->call;                                                                                   \
    }

VIEW_METHOD(nativeGoBack, traverse_history_by_delta(-1))
VIEW_METHOD(nativeGoForward, traverse_history_by_delta(1))
VIEW_METHOD(nativeReload, reload())
VIEW_METHOD(nativeStopLoading, stop_loading())
VIEW_METHOD(nativeFindInPageNextMatch, find_in_page_next_match())
VIEW_METHOD(nativeFindInPagePreviousMatch, find_in_page_previous_match())
VIEW_METHOD(nativeFindInPageEnd, find_in_page_end())
#undef VIEW_METHOD

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_WebViewImplementation_nativeSetVisible(JNIEnv*, jobject, jlong instance, jboolean visible)
{
    if (auto* view = reinterpret_cast<AndroidView*>(instance))
        view->set_visible(visible);
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_WebViewImplementation_nativeFindInPage(JNIEnv* env, jobject, jlong instance, jstring query)
{
    auto* view = reinterpret_cast<AndroidView*>(instance);
    if (!view)
        return;
    auto const* characters = env->GetStringChars(query, nullptr);
    auto length = env->GetStringLength(query);
    view->find_in_page(Utf16String::from_utf16({ reinterpret_cast<char16_t const*>(characters), static_cast<size_t>(length) }));
    env->ReleaseStringChars(query, characters);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_serenityos_ladybird_WebViewImplementation_nativeCurrentHardwareBuffer(JNIEnv* env, jobject, jlong instance)
{
    if (auto* view = reinterpret_cast<AndroidView*>(instance))
        return view->current_hardware_buffer(env);
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_WebViewImplementation_nativeSetMaximumFramesPerSecond(JNIEnv*, jobject, jlong instance, jfloat maximum_frames_per_second)
{
    if (auto* view = reinterpret_cast<AndroidView*>(instance))
        view->set_maximum_frames_per_second(maximum_frames_per_second);
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_WebViewImplementation_nativeScroll(JNIEnv*, jobject, jlong instance, jfloat x, jfloat y, jfloat delta_x, jfloat delta_y, jint phase)
{
    // The Java side numbers the phases: 0 is a drag, 1 is a fling's momentum, and 2 ends the gesture.
    static constexpr Web::ScrollGesturePhase phases[] = { Web::ScrollGesturePhase::Ongoing, Web::ScrollGesturePhase::Momentum, Web::ScrollGesturePhase::Ended };
    if (phase < 0 || phase > 2)
        return;
    if (auto* view = reinterpret_cast<AndroidView*>(instance))
        view->scroll(x, y, delta_x, delta_y, phases[phase]);
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_WebViewImplementation_nativeCommitText(JNIEnv* env, jobject, jlong instance, jstring text)
{
    auto* view = reinterpret_cast<AndroidView*>(instance);
    if (!view)
        return;
    auto const* characters = env->GetStringChars(text, nullptr);
    auto length = env->GetStringLength(text);
    view->commit_text(Utf16String::from_utf16({ reinterpret_cast<char16_t const*>(characters), static_cast<size_t>(length) }));
    env->ReleaseStringChars(text, characters);
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_WebViewImplementation_nativePressKey(JNIEnv*, jobject, jlong instance, jint key)
{
    // The Java side numbers the keys: 0 is Backspace, and 1 is Enter.
    if (auto* view = reinterpret_cast<AndroidView*>(instance))
        view->press_key(key == 0 ? AndroidView::EditingKey::Backspace : AndroidView::EditingKey::Enter);
}
