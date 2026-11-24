/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

#include <AK/Function.h>
#include <AK/HashMap.h>
#include <AK/Utf16String.h>
#include <Hub/HubClientEndpoint.h>
#include <Hub/HubServerEndpoint.h>
#include <LibCore/Process.h>
#include <LibIPC/ConnectionToServer.h>
#include <LibWebView/PageBackingStores.h>
#include <jni.h>

struct AHardwareBuffer;

namespace Ladybird {

class AndroidView;

// The Android UI's end of its connection to the Hub, which runs the browser in a process of its own.
class HubClient final
    : public IPC::ConnectionToServer<HubClientEndpoint, HubServerEndpoint>
    , public HubClientEndpoint {
    C_OBJECT_ABSTRACT(HubClient)

public:
    static ErrorOr<NonnullRefPtr<HubClient>> launch(ByteString const& helper_directory);
    static HubClient* the();

    explicit HubClient(NonnullOwnPtr<IPC::Transport>);
    virtual ~HubClient() override;

    void register_view(u64 view_id, AndroidView&);
    void unregister_view(u64 view_id);

private:
    virtual void die() override;

    AndroidView* find_view(u64 view_id);

    virtual void view_install_backing_stores(u64 view_id, u64 page_id, Vector<i32> bitmap_ids, Vector<Gfx::SharedImage> backing_stores) override;
    virtual void view_add_backing_stores(u64 view_id, Vector<i32> bitmap_ids, Vector<Gfx::SharedImage> backing_stores) override;
    virtual void view_retire_backing_stores(u64 view_id, Vector<i32> bitmap_ids) override;
    virtual void view_present_backing_store(u64 view_id, u64 page_id, i32 bitmap_id, Gfx::IntSize size, double device_pixel_ratio) override;
    virtual void view_accept_presented_backing_store(u64 view_id, i32 bitmap_id, Gfx::IntRect damage_rect) override;
    virtual void view_keep_front_backing_store_as_backup(u64 view_id) override;
    virtual void view_clear_backup_backing_store(u64 view_id) override;
    virtual void view_clear_backing_stores(u64 view_id) override;
    virtual void view_load_start(u64 view_id) override;
    virtual void view_title_change(u64 view_id, Utf16String title) override;
    virtual void view_url_change(u64 view_id, URL::URL url) override;
    virtual void view_state_changed(u64 view_id, Hub::ViewState state) override;
    virtual void view_input_method_state_change(u64 view_id) override;

#include "HubClientStubs.inc"

    Optional<Core::Process> m_hub_process;
    HashMap<u64, AndroidView*> m_views;
    bool m_did_initialize { false };
};

// One page shown in an Android View. Frames arrive from the Compositor through the Hub as hardware buffers, which the
// Java side draws straight from, or without a GPU, as shared bitmaps in CPU memory, which it copies into its own Bitmap.
class AndroidView {
    AK_ALLOC_WITH_KMALLOC;

public:
    static ErrorOr<NonnullOwnPtr<AndroidView>> create(jobject java_instance, double device_pixel_ratio, bool is_private, bool websites_prefer_dark);
    ~AndroidView();

    jobject java_instance() const { return m_java_instance; }

    void set_viewport(int width, int height);
    void set_device_pixel_ratio(double);
    void set_maximum_frames_per_second(double);
    void load(StringView url);
    void traverse_history_by_delta(int delta);
    void reload();
    void stop_loading();
    void set_visible(bool);
    void find_in_page(Utf16String const& query);
    void find_in_page_next_match();
    void find_in_page_previous_match();
    void find_in_page_end();
    void touch(int action, float x, float y);
    void scroll(float x, float y, float delta_x, float delta_y, Web::ScrollGesturePhase);
    void commit_text(Utf16String const&);
    enum class EditingKey {
        Backspace,
        Enter,
    };
    void press_key(EditingKey);
    void draw_into(JNIEnv*, jobject bitmap);
    // The android.hardware.HardwareBuffer of the frame to show, as a new local reference, or null.
    jobject current_hardware_buffer(JNIEnv*);

    // From the Hub.
    void did_install_backing_stores(u64 page_id, Vector<i32> bitmap_ids, Vector<Gfx::SharedImage>);
    void did_add_backing_stores(Vector<i32> bitmap_ids, Vector<Gfx::SharedImage>);
    void did_retire_backing_stores(Vector<i32> const& bitmap_ids);
    void did_present_backing_store(u64 page_id, i32 bitmap_id, Gfx::IntSize, double device_pixel_ratio);
    void did_keep_front_as_backup() { m_backing_stores.keep_front_as_backup(); }
    void did_clear_backup() { m_backing_stores.clear_backup(); }
    void did_clear_backing_stores();
    void did_start_loading();
    void did_change_url(URL::URL const&);
    void did_change_state(Hub::ViewState const&);

    static jclass s_class;
    static jmethodID s_invalidate_layout_method;
    static jmethodID s_on_load_start_method;
    static jmethodID s_on_input_method_state_changed_method;
    static jmethodID s_on_state_changed_method;

private:
    AndroidView(jobject java_instance, u64 view_id, double device_pixel_ratio);

    void send_viewport();
    void invalidate();
    void forget_java_hardware_buffers();

    jobject m_java_instance { nullptr };
    u64 m_view_id { 0 };
    u64 m_next_input_id { 1 };
    double m_device_pixel_ratio { 1.0 };
    int m_width { 0 };
    int m_height { 0 };
    WebView::PageBackingStores m_backing_stores;
    // Java wrappers of the hardware buffers, made once per buffer so that the Java side can keep a Bitmap for each.
    HashMap<AHardwareBuffer const*, jobject> m_java_hardware_buffers;
    u64 m_backing_stores_page_id { 0 };
    URL::URL m_url;
    bool m_input_method_is_enabled { false };
};

}
