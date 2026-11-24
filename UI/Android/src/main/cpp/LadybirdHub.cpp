/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "LadybirdHub.h"
#include "JNIHelpers.h"
#include <AK/Utf16String.h>
#include <LibCore/Environment.h>
#include <LibCore/Resource.h>
#include <LibCore/Socket.h>
#include <LibCore/System.h>
#include <LibGfx/Bitmap.h>
#include <LibGfx/SharedImageBuffer.h>
#include <LibGfx/SystemTheme.h>
#include <LibIPC/Transport.h>
#include <LibURL/Parser.h>
#include <LibWebView/Utilities.h>
#include <android/bitmap.h>
#include <android/hardware_buffer_jni.h>
#include <sys/socket.h>

namespace Ladybird {

static HubClient* s_the;

jclass AndroidView::s_class;
jmethodID AndroidView::s_invalidate_layout_method;
jmethodID AndroidView::s_on_load_start_method;
jmethodID AndroidView::s_on_input_method_state_changed_method;
jmethodID AndroidView::s_on_state_changed_method;

ErrorOr<NonnullRefPtr<HubClient>> HubClient::launch(ByteString const& helper_directory)
{
    auto hub_path = ByteString::formatted("{}/libHub.so", helper_directory);

    int socket_fds[2] {};
    TRY(Core::System::socketpair(AF_LOCAL, SOCK_STREAM | SOCK_CLOEXEC, 0, socket_fds));

    // FIXME: Ladybird's Linux sandbox collides with the one Android puts every app in, so it is off for now.
    Vector<ByteString> arguments { "--disable-sandbox"sv };
    Core::ProcessSpawnOptions spawn_options {
        .name = "Hub"sv,
        .executable = hub_path,
        .arguments = arguments,
    };
    spawn_options.file_actions.append(Core::FileAction::DupFd { .write_fd = socket_fds[1], .fd = socket_fds[1] });
    TRY(Core::Environment::set("SOCKET_TAKEOVER"sv, ByteString::formatted("ipc:{}", socket_fds[1]), Core::Environment::Overwrite::Yes));
    auto process = TRY(Core::Process::spawn(spawn_options));
    TRY(Core::Environment::unset("SOCKET_TAKEOVER"sv));
    TRY(Core::System::close(socket_fds[1]));

    auto ipc_socket = TRY(Core::LocalSocket::adopt_fd(socket_fds[0]));
    TRY(ipc_socket->set_blocking(true));
    auto client = adopt_ref(*new HubClient(make<IPC::Transport>(move(ipc_socket))));
    client->m_hub_process = move(process);

    // NB: The Hub's answer only matters for a desktop UI, which restores windows and stores from it.
    auto state_or_error = client->try_initialize();
    if (state_or_error.is_error())
        return Error::from_string_literal("The Hub ended before it initialized");
    client->m_did_initialize = true;
    client->set_peer_owns_this_process(true);
    client->async_ui_did_start();
    return client;
}

HubClient* HubClient::the()
{
    return s_the;
}

HubClient::HubClient(NonnullOwnPtr<IPC::Transport> transport)
    : IPC::ConnectionToServer<HubClientEndpoint, HubServerEndpoint>(*this, move(transport))
{
    s_the = this;
}

HubClient::~HubClient()
{
    if (s_the == this)
        s_the = nullptr;
}

void HubClient::die()
{
    if (!m_did_initialize)
        return;
    // The UI has nothing left to show without the Hub.
    dbgln("The Hub has gone away");
    Core::Process::terminate_immediately(1);
}

void HubClient::register_view(u64 view_id, AndroidView& view)
{
    m_views.set(view_id, &view);
}

void HubClient::unregister_view(u64 view_id)
{
    m_views.remove(view_id);
}

AndroidView* HubClient::find_view(u64 view_id)
{
    return m_views.get(view_id).value_or(nullptr);
}

void HubClient::view_install_backing_stores(u64 view_id, u64 page_id, Vector<i32> bitmap_ids, Vector<Gfx::SharedImage> backing_stores)
{
    if (auto* view = find_view(view_id))
        view->did_install_backing_stores(page_id, move(bitmap_ids), move(backing_stores));
    else if (!bitmap_ids.is_empty())
        async_release_backing_store(page_id, bitmap_ids[0]);
}

void HubClient::view_add_backing_stores(u64 view_id, Vector<i32> bitmap_ids, Vector<Gfx::SharedImage> backing_stores)
{
    if (auto* view = find_view(view_id))
        view->did_add_backing_stores(move(bitmap_ids), move(backing_stores));
}

void HubClient::view_retire_backing_stores(u64 view_id, Vector<i32> bitmap_ids)
{
    if (auto* view = find_view(view_id))
        view->did_retire_backing_stores(bitmap_ids);
}

void HubClient::view_present_backing_store(u64 view_id, u64 page_id, i32 bitmap_id, Gfx::IntSize size, double device_pixel_ratio)
{
    if (auto* view = find_view(view_id))
        view->did_present_backing_store(page_id, bitmap_id, size, device_pixel_ratio);
    else
        async_release_backing_store(page_id, bitmap_id);
}

void HubClient::view_accept_presented_backing_store(u64, i32, Gfx::IntRect)
{
}

void HubClient::view_keep_front_backing_store_as_backup(u64 view_id)
{
    if (auto* view = find_view(view_id))
        view->did_keep_front_as_backup();
}

void HubClient::view_clear_backup_backing_store(u64 view_id)
{
    if (auto* view = find_view(view_id))
        view->did_clear_backup();
}

void HubClient::view_clear_backing_stores(u64 view_id)
{
    if (auto* view = find_view(view_id))
        view->did_clear_backing_stores();
}

void HubClient::view_load_start(u64 view_id)
{
    if (auto* view = find_view(view_id))
        view->did_start_loading();
}

void HubClient::view_title_change(u64, Utf16String)
{
}

void HubClient::view_url_change(u64 view_id, URL::URL url)
{
    if (auto* view = find_view(view_id))
        view->did_change_url(url);
}

void HubClient::view_state_changed(u64 view_id, Hub::ViewState state)
{
    if (auto* view = find_view(view_id))
        view->did_change_state(state);
}

void HubClient::view_input_method_state_change(u64)
{
    // NB: The new state arrives in view_state_changed(), which is all the Android UI looks at.
}

static Core::AnonymousBuffer load_default_theme()
{
    auto theme_ini = MUST(Core::Resource::load_from_uri("resource://themes/Default.ini"sv));
    return MUST(Gfx::load_system_theme(theme_ini->filesystem_path().to_byte_string()));
}

ErrorOr<NonnullOwnPtr<AndroidView>> AndroidView::create(jobject java_instance, double device_pixel_ratio, bool is_private, bool websites_prefer_dark)
{
    auto* hub = HubClient::the();
    if (!hub)
        return Error::from_string_literal("Not connected to the Hub");

    auto response = hub->create_view({
        .is_private = is_private ? WebView::IsPrivate::Yes : WebView::IsPrivate::No,
        .device_pixel_ratio = device_pixel_ratio,
        .maximum_frames_per_second = 60,
        .display_id = {},
        .screen_shows_high_dynamic_range = false,
        .system_theme = load_default_theme(),
        .screen_rects = {},
        .owner_view_id = {},
        .websites_prefer_dark = websites_prefer_dark,
        .is_tab = true,
    });
    auto view = adopt_own(*new AndroidView(java_instance, response.view_id(), device_pixel_ratio));
    hub->register_view(view->m_view_id, *view);
    view->set_visible(true);
    return view;
}

AndroidView::AndroidView(jobject java_instance, u64 view_id, double device_pixel_ratio)
    : m_java_instance(java_instance)
    , m_view_id(view_id)
    , m_device_pixel_ratio(device_pixel_ratio)
{
}

AndroidView::~AndroidView()
{
    forget_java_hardware_buffers();
    if (auto* hub = HubClient::the()) {
        hub->unregister_view(m_view_id);
        hub->async_destroy_view(m_view_id);
    }
}

void AndroidView::send_viewport()
{
    if (m_width <= 0 || m_height <= 0)
        return;
    if (auto* hub = HubClient::the())
        hub->async_view_set_viewport(m_view_id, Web::DevicePixelSize { m_width, m_height }, m_device_pixel_ratio);
}

void AndroidView::set_viewport(int width, int height)
{
    m_width = width;
    m_height = height;
    send_viewport();
}

void AndroidView::set_device_pixel_ratio(double device_pixel_ratio)
{
    m_device_pixel_ratio = device_pixel_ratio;
    send_viewport();
}

void AndroidView::set_maximum_frames_per_second(double maximum_frames_per_second)
{
    if (auto* hub = HubClient::the())
        hub->async_view_set_display_metadata(m_view_id, {}, maximum_frames_per_second, false);
}

void AndroidView::load(StringView url_string)
{
    auto url = URL::Parser::basic_parse(url_string);
    if (!url.has_value())
        url = URL::Parser::basic_parse(ByteString::formatted("https://{}", url_string));
    if (!url.has_value())
        return;
    if (auto* hub = HubClient::the())
        hub->async_view_load(m_view_id, *url, Web::Bindings::NavigationHistoryBehavior::Auto);
}

void AndroidView::touch(int action, float x, float y)
{
    // Android's MotionEvent actions: 0 is down, 1 is up, and 2 is a move.
    Web::MouseEvent::Type type;
    switch (action) {
    case 0:
        type = Web::MouseEvent::Type::MouseDown;
        break;
    case 1:
        type = Web::MouseEvent::Type::MouseUp;
        break;
    case 2:
        type = Web::MouseEvent::Type::MouseMove;
        break;
    default:
        return;
    }

    Web::MouseEvent event {};
    event.type = type;
    event.position = Web::DevicePixelPoint { static_cast<int>(x), static_cast<int>(y) };
    event.screen_position = event.position;
    event.button = type == Web::MouseEvent::Type::MouseMove ? Web::UIEvents::MouseButton::None : Web::UIEvents::MouseButton::Primary;
    event.buttons = type == Web::MouseEvent::Type::MouseUp ? Web::UIEvents::MouseButton::None : Web::UIEvents::MouseButton::Primary;
    event.click_count = type == Web::MouseEvent::Type::MouseMove ? 0 : 1;

    auto input_id = m_next_input_id++;
    event.id = input_id;
    if (auto* hub = HubClient::the())
        hub->async_view_enqueue_mouse_event(m_view_id, input_id, move(event));
}

void AndroidView::scroll(float x, float y, float delta_x, float delta_y, Web::ScrollGesturePhase phase)
{
    Web::MouseEvent event {};
    event.type = Web::MouseEvent::Type::MouseWheel;
    event.position = Web::DevicePixelPoint { static_cast<int>(x), static_cast<int>(y) };
    event.screen_position = event.position;
    event.wheel_delta_x = delta_x;
    event.wheel_delta_y = delta_y;
    event.wheel_tick_120ths_x = Web::wheel_tick_120ths_from_pixel_delta(delta_x, Web::WheelDeltaPrecision::Precise);
    event.wheel_tick_120ths_y = Web::wheel_tick_120ths_from_pixel_delta(delta_y, Web::WheelDeltaPrecision::Precise);
    event.wheel_delta_precision = Web::WheelDeltaPrecision::Precise;
    event.scroll_gesture_phase = phase;

    auto input_id = m_next_input_id++;
    event.id = input_id;
    if (auto* hub = HubClient::the())
        hub->async_view_enqueue_mouse_event(m_view_id, input_id, move(event));
}

void AndroidView::commit_text(Utf16String const& text)
{
    if (auto* hub = HubClient::the())
        hub->async_view_commit_text_from_input_method(m_view_id, text, 0, 0);
}

void AndroidView::press_key(EditingKey editing_key)
{
    auto* hub = HubClient::the();
    if (!hub)
        return;

    auto key = editing_key == EditingKey::Backspace ? Web::UIEvents::KeyCode::Key_Backspace : Web::UIEvents::KeyCode::Key_Return;
    auto code_point = editing_key == EditingKey::Backspace ? '\b' : '\r';
    for (auto type : { Web::KeyEvent::Type::KeyDown, Web::KeyEvent::Type::KeyUp }) {
        Web::KeyEvent event {};
        event.type = type;
        event.key = key;
        event.code_point = code_point;
        auto input_id = m_next_input_id++;
        event.id = input_id;
        hub->async_view_enqueue_key_event(m_view_id, input_id, move(event));
    }
}

void AndroidView::traverse_history_by_delta(int delta)
{
    if (auto* hub = HubClient::the())
        hub->async_view_traverse_the_history_by_delta(m_view_id, delta);
}

void AndroidView::reload()
{
    if (auto* hub = HubClient::the())
        hub->async_view_reload(m_view_id, WebView::BypassCache::No);
}

void AndroidView::stop_loading()
{
    if (auto* hub = HubClient::the())
        hub->async_view_stop_loading(m_view_id);
}

// Only the tab on screen is visible and focused, so that pages in other tabs throttle their work.
void AndroidView::set_visible(bool visible)
{
    auto* hub = HubClient::the();
    if (!hub)
        return;
    hub->async_view_set_system_visibility_state(m_view_id, visible ? Web::HTML::VisibilityState::Visible : Web::HTML::VisibilityState::Hidden);
    hub->async_view_set_has_system_focus(m_view_id, visible);
}

void AndroidView::find_in_page(Utf16String const& query)
{
    if (auto* hub = HubClient::the())
        hub->async_view_find_in_page(m_view_id, query, CaseSensitivity::CaseInsensitive, true);
}

void AndroidView::find_in_page_next_match()
{
    if (auto* hub = HubClient::the())
        hub->async_view_find_in_page_next_match(m_view_id);
}

void AndroidView::find_in_page_previous_match()
{
    if (auto* hub = HubClient::the())
        hub->async_view_find_in_page_previous_match(m_view_id);
}

void AndroidView::find_in_page_end()
{
    if (auto* hub = HubClient::the())
        hub->async_view_find_in_page_end(m_view_id);
}

void AndroidView::did_change_state(Hub::ViewState const& state)
{
    JavaEnvironment env(global_vm);

    auto url = env.jstring_from_ak_string(state.displayed_url.serialize());
    auto title = env.jstring_from_ak_string(state.title.to_utf8());
    env.get()->CallVoidMethod(m_java_instance, s_on_state_changed_method, url, title, state.is_loading, state.can_navigate_back, state.can_navigate_forward);
    env.get()->DeleteLocalRef(url);
    env.get()->DeleteLocalRef(title);

    auto input_method_is_enabled = state.input_method_state.is_enabled;
    if (m_input_method_is_enabled == input_method_is_enabled)
        return;
    m_input_method_is_enabled = input_method_is_enabled;
    env.get()->CallVoidMethod(m_java_instance, s_on_input_method_state_changed_method, input_method_is_enabled);
}

void AndroidView::draw_into(JNIEnv* env, jobject bitmap)
{
    AndroidBitmapInfo info {};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        return;

    auto* destination = static_cast<u8*>(pixels);
    auto frame = m_backing_stores.displayed_frame();
    if (frame.has_value() && frame->shared_image_buffer && frame->shared_image_buffer->bitmap_if_present()) {
        auto source = frame->shared_image_buffer->bitmap();
        auto width = min<int>(info.width, min(frame->size.width(), source->width()));
        auto height = min<int>(info.height, min(frame->size.height(), source->height()));
        for (int y = 0; y < static_cast<int>(info.height); ++y) {
            auto* row = reinterpret_cast<u32*>(destination + y * info.stride);
            if (y >= height) {
                memset(row, 0xff, info.width * 4);
                continue;
            }
            auto const* source_row = source->scanline(y);
            for (int x = 0; x < width; ++x) {
                // Ladybird's bitmaps are BGRA in memory, and Android's are RGBA.
                auto pixel = source_row[x];
                row[x] = (pixel & 0xff00ff00) | ((pixel & 0x00ff0000) >> 16) | ((pixel & 0x000000ff) << 16);
            }
            for (int x = width; x < static_cast<int>(info.width); ++x)
                row[x] = 0xffffffff;
        }
    } else {
        memset(destination, 0xff, info.stride * info.height);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}

jobject AndroidView::current_hardware_buffer(JNIEnv* env)
{
    auto frame = m_backing_stores.displayed_frame();
    if (!frame.has_value() || !frame->shared_image_buffer)
        return nullptr;
    auto const* hardware_buffer = frame->shared_image_buffer->android_hardware_buffer();
    if (!hardware_buffer)
        return nullptr;

    jobject java_buffer = m_java_hardware_buffers.get(hardware_buffer->buffer()).value_or(nullptr);
    if (!java_buffer) {
        auto local = AHardwareBuffer_toHardwareBuffer(env, hardware_buffer->buffer());
        if (!local)
            return nullptr;
        java_buffer = env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
        m_java_hardware_buffers.set(hardware_buffer->buffer(), java_buffer);
    }
    return env->NewLocalRef(java_buffer);
}

void AndroidView::forget_java_hardware_buffers()
{
    if (m_java_hardware_buffers.is_empty())
        return;
    JavaEnvironment env(global_vm);
    for (auto& it : m_java_hardware_buffers)
        env.get()->DeleteGlobalRef(it.value);
    m_java_hardware_buffers.clear();
}

void AndroidView::invalidate()
{
    JavaEnvironment env(global_vm);
    env.get()->CallVoidMethod(m_java_instance, s_invalidate_layout_method);
}

void AndroidView::did_install_backing_stores(u64 page_id, Vector<i32> bitmap_ids, Vector<Gfx::SharedImage> backing_stores)
{
    auto front_bitmap_id = bitmap_ids.first();
    forget_java_hardware_buffers();
    if (auto result = m_backing_stores.install(move(bitmap_ids), move(backing_stores)); result.is_error()) {
        dbgln("Could not install backing stores for page {}: {}", page_id, result.error());
        if (auto* hub = HubClient::the())
            hub->async_release_backing_store(page_id, front_bitmap_id);
        return;
    }
    m_backing_stores_page_id = page_id;
}

void AndroidView::did_add_backing_stores(Vector<i32> bitmap_ids, Vector<Gfx::SharedImage> backing_stores)
{
    if (auto result = m_backing_stores.add(move(bitmap_ids), move(backing_stores)); result.is_error())
        dbgln("Could not add backing stores: {}", result.error());
}

void AndroidView::did_retire_backing_stores(Vector<i32> const& bitmap_ids)
{
    forget_java_hardware_buffers();
    m_backing_stores.retire(bitmap_ids);
}

void AndroidView::did_clear_backing_stores()
{
    forget_java_hardware_buffers();
    m_backing_stores.clear();
}

void AndroidView::did_present_backing_store(u64 page_id, i32 bitmap_id, Gfx::IntSize size, double device_pixel_ratio)
{
    auto result = m_backing_stores.present(bitmap_id, size, device_pixel_ratio);
    if (auto* hub = HubClient::the(); hub && result.bitmap_id_to_release >= 0)
        hub->async_release_backing_store(page_id, result.bitmap_id_to_release);
    invalidate();
}

void AndroidView::did_start_loading()
{
    JavaEnvironment env(global_vm);
    auto url_string = env.jstring_from_ak_string(m_url.serialize());
    env.get()->CallVoidMethod(m_java_instance, s_on_load_start_method, url_string, false);
    env.get()->DeleteLocalRef(url_string);
}

void AndroidView::did_change_url(URL::URL const& url)
{
    m_url = url;
}

}
