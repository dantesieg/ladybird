/*
 * Copyright (c) 2026-present, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include <AK/Assertions.h>
#include <AK/Math.h>
#include <AK/Platform.h>
#include <Compositor/VSyncScheduler.h>
#include <LibCore/Timer.h>

#if defined(AK_OS_MACOS)
#    include <AK/Atomic.h>
#    include <AK/AtomicRefCounted.h>
#    include <AK/NonnullRefPtr.h>
#    include <AK/RefPtr.h>
#    include <CoreGraphics/CoreGraphics.h>
#    include <CoreVideo/CoreVideo.h>
#    include <LibCore/EventLoop.h>
#elif defined(AK_OS_ANDROID)
#    include <AK/Atomic.h>
#    include <AK/AtomicRefCounted.h>
#    include <AK/NonnullRefPtr.h>
#    include <LibCore/EventLoop.h>
#    include <LibThreading/Thread.h>
#    include <android/choreographer.h>
#    include <android/looper.h>
#    include <errno.h>
#    include <sys/eventfd.h>
#    include <unistd.h>
#endif

namespace Compositor {

class TimerVSyncScheduler final : public VSyncScheduler {
public:
    AK_ALLOC_WITH_KMALLOC;

    explicit TimerVSyncScheduler(Function<void(MonotonicTime)>&& tick_callback)
        : m_tick_callback(move(tick_callback))
        , m_timer(Core::Timer::create_single_shot(AK::Duration::from_milliseconds(0), [this] {
            fire();
        }))
    {
    }

    virtual ~TimerVSyncScheduler() override
    {
        m_timer->on_timeout = {};
        m_timer->stop();
    }

    virtual void schedule(double refresh_rate) override
    {
        VERIFY(refresh_rate == refresh_rate);
        VERIFY(refresh_rate > 0);
        VERIFY(refresh_rate < AK::Infinity<double>);

        auto refresh_rate_changed = m_refresh_rate != refresh_rate;
        m_refresh_rate = refresh_rate;
        auto now = MonotonicTime::now();
        if (!m_next_tick_time.has_value() || refresh_rate_changed)
            m_next_tick_time = now + frame_interval();
        else
            advance_next_tick_time_past(now);

        if (m_timer->is_active() && !refresh_rate_changed)
            return;

        arm_timer(now);
    }

    virtual Optional<MonotonicTime> most_recent_tick_time(MonotonicTime now, double refresh_rate) override
    {
        if (!m_next_tick_time.has_value() || m_refresh_rate != refresh_rate)
            return {};

        advance_next_tick_time_past(now);
        return *m_next_tick_time - frame_interval();
    }

private:
    void fire()
    {
        auto now = MonotonicTime::now();
        advance_next_tick_time_past(now);
        auto frame_time = *m_next_tick_time - frame_interval();
        m_tick_callback(frame_time);
    }

    AK::Duration frame_interval() const
    {
        return AK::Duration::from_nanoseconds(static_cast<i64>((1'000'000'000.0 / m_refresh_rate) + 0.5));
    }

    void advance_next_tick_time_past(MonotonicTime now)
    {
        VERIFY(m_next_tick_time.has_value());
        if (*m_next_tick_time > now)
            return;

        auto interval = frame_interval();
        auto intervals_to_advance = (now - *m_next_tick_time).to_nanoseconds() / interval.to_nanoseconds() + 1;
        *m_next_tick_time += AK::Duration::from_nanoseconds(intervals_to_advance * interval.to_nanoseconds());
    }

    void arm_timer(MonotonicTime now)
    {
        VERIFY(m_next_tick_time.has_value());
        advance_next_tick_time_past(now);
        VERIFY(*m_next_tick_time > now);

        // NB: The timer is armed against a fixed display grid. Advancing by whole intervals keeps request and update
        //     latency from shifting later ticks.
        m_timer->restart(*m_next_tick_time - now);
    }

    Function<void(MonotonicTime)> m_tick_callback;
    double m_refresh_rate { 60.0 };
    RefPtr<Core::Timer> m_timer;
    Optional<MonotonicTime> m_next_tick_time;
};

#if defined(AK_OS_MACOS)

static constexpr int display_link_idle_stop_delay_ms = 250;

static CVReturn display_link_callback(CVDisplayLinkRef, CVTimeStamp const*, CVTimeStamp const*, CVOptionFlags, CVOptionFlags*, void* context);

class DisplayLinkState final : public AtomicRefCounted<DisplayLinkState> {
public:
    static RefPtr<DisplayLinkState> create(u64 display_id, Core::EventLoop& event_loop, Function<void(MonotonicTime)>&& tick_callback)
    {
        CVDisplayLinkRef display_link = nullptr;

#    pragma clang diagnostic push
#    pragma clang diagnostic ignored "-Wdeprecated-declarations"
        auto result = CVDisplayLinkCreateWithCGDisplay(static_cast<CGDirectDisplayID>(display_id), &display_link);
#    pragma clang diagnostic pop

        if (result != kCVReturnSuccess || !display_link)
            return nullptr;

        auto state = adopt_ref(*new DisplayLinkState(event_loop, display_link));

#    pragma clang diagnostic push
#    pragma clang diagnostic ignored "-Wdeprecated-declarations"
        result = CVDisplayLinkSetOutputCallback(display_link, display_link_callback, state.ptr());
#    pragma clang diagnostic pop

        if (result != kCVReturnSuccess)
            return nullptr;

        state->tick_callback = move(tick_callback);
        return state;
    }

    ~DisplayLinkState()
    {
        stop_display_link();
    }

    bool request_tick();
    void stop_display_link();
    void schedule_display_link_stop_if_idle();
    void stop_display_link_if_idle();
    bool consume_tick_request();
    bool is_valid() const;
    bool is_running() const;

    Core::EventLoop& event_loop;
    Function<void(MonotonicTime)> tick_callback;
    Atomic<bool> invalidated { false };
    Atomic<bool> tick_requested { false };
    Atomic<i64> most_recent_tick_time_nanoseconds { 0 };
    CVDisplayLinkRef display_link { nullptr };
    NonnullRefPtr<Core::Timer> idle_stop_timer;

private:
    DisplayLinkState(Core::EventLoop& event_loop, CVDisplayLinkRef display_link)
        : event_loop(event_loop)
        , display_link(display_link)
        , idle_stop_timer(Core::Timer::create_single_shot(AK::Duration::from_milliseconds(display_link_idle_stop_delay_ms), [this] {
            stop_display_link_if_idle();
        }))
    {
    }
};

bool DisplayLinkState::request_tick()
{
    if (invalidated.load() || !display_link)
        return false;

    tick_requested.store(true);
    idle_stop_timer->stop();

#    pragma clang diagnostic push
#    pragma clang diagnostic ignored "-Wdeprecated-declarations"
    auto is_running = CVDisplayLinkIsRunning(display_link);
#    pragma clang diagnostic pop

    if (is_running)
        return true;

#    pragma clang diagnostic push
#    pragma clang diagnostic ignored "-Wdeprecated-declarations"
    auto result = CVDisplayLinkStart(display_link);
#    pragma clang diagnostic pop

    if (result == kCVReturnSuccess || result == kCVReturnDisplayLinkAlreadyRunning)
        return true;

    tick_requested.store(false);
    return false;
}

void DisplayLinkState::stop_display_link()
{
    invalidated.store(true);
    tick_requested.store(false);
    idle_stop_timer->on_timeout = {};
    idle_stop_timer->stop();

    if (!display_link)
        return;

#    pragma clang diagnostic push
#    pragma clang diagnostic ignored "-Wdeprecated-declarations"
    CVDisplayLinkStop(display_link);
#    pragma clang diagnostic pop
    CVDisplayLinkRelease(display_link);
    display_link = nullptr;
}

void DisplayLinkState::schedule_display_link_stop_if_idle()
{
    if (invalidated.load() || tick_requested.load() || !display_link)
        return;

    idle_stop_timer->restart(AK::Duration::from_milliseconds(display_link_idle_stop_delay_ms));
}

void DisplayLinkState::stop_display_link_if_idle()
{
    if (invalidated.load() || tick_requested.load() || !display_link)
        return;

#    pragma clang diagnostic push
#    pragma clang diagnostic ignored "-Wdeprecated-declarations"
    CVDisplayLinkStop(display_link);
#    pragma clang diagnostic pop
}

bool DisplayLinkState::consume_tick_request()
{
    if (invalidated.load())
        return false;

    if (!tick_requested.exchange(false))
        return false;

    return true;
}

bool DisplayLinkState::is_valid() const
{
    return !invalidated.load();
}

bool DisplayLinkState::is_running() const
{
    if (invalidated.load() || !display_link)
        return false;

#    pragma clang diagnostic push
#    pragma clang diagnostic ignored "-Wdeprecated-declarations"
    auto running = CVDisplayLinkIsRunning(display_link);
#    pragma clang diagnostic pop
    return running;
}

class CVDisplayLinkVSyncScheduler final : public VSyncScheduler {
public:
    AK_ALLOC_WITH_KMALLOC;

    static OwnPtr<CVDisplayLinkVSyncScheduler> try_create(u64 display_id, Function<void(MonotonicTime)>&& tick_callback)
    {
        auto state = DisplayLinkState::create(display_id, Core::EventLoop::current(), move(tick_callback));
        if (!state)
            return nullptr;
        return adopt_own(*new CVDisplayLinkVSyncScheduler(state.release_nonnull()));
    }

    explicit CVDisplayLinkVSyncScheduler(NonnullRefPtr<DisplayLinkState> state)
        : m_state(move(state))
    {
    }

    virtual ~CVDisplayLinkVSyncScheduler() override
    {
        m_state->stop_display_link();
    }

    virtual void schedule(double) override
    {
        m_state->request_tick();
    }

    virtual Optional<MonotonicTime> most_recent_tick_time(MonotonicTime now, double refresh_rate) override
    {
        if (!m_state->is_running())
            return {};

        auto tick_time_nanoseconds = m_state->most_recent_tick_time_nanoseconds.load();
        if (tick_time_nanoseconds == 0 || tick_time_nanoseconds > now.nanoseconds())
            return {};

        auto age_nanoseconds = now.nanoseconds() - tick_time_nanoseconds;
        auto frame_interval_nanoseconds = static_cast<i64>((1'000'000'000.0 / refresh_rate) + 0.5);
        if (age_nanoseconds >= frame_interval_nanoseconds)
            return {};
        return now - AK::Duration::from_nanoseconds(age_nanoseconds);
    }

private:
    NonnullRefPtr<DisplayLinkState> m_state;
};

static CVReturn display_link_callback(CVDisplayLinkRef, CVTimeStamp const*, CVTimeStamp const*, CVOptionFlags, CVOptionFlags*, void* context)
{
    auto state = NonnullRefPtr { *static_cast<DisplayLinkState*>(context) };
    auto frame_time = MonotonicTime::now();
    state->most_recent_tick_time_nanoseconds.store(frame_time.nanoseconds());
    if (!state->consume_tick_request())
        return kCVReturnSuccess;

    // NB: Sample the shared monotonic clock on the display-link thread so deferred delivery does not shift the
    //     frame timestamp to the Compositor event-loop wakeup time.
    state->event_loop.deferred_invoke([state = move(state), frame_time] {
        if (!state->is_valid())
            return;
        state->tick_callback(frame_time);
        state->schedule_display_link_stop_if_idle();
    });
    return kCVReturnSuccess;
}

OwnPtr<VSyncScheduler> create_vsync_scheduler(Optional<u64> display_id, Function<void(MonotonicTime)>&& tick_callback)
{
    if (display_id.has_value()) {
        if (auto scheduler = CVDisplayLinkVSyncScheduler::try_create(*display_id, move(tick_callback)); scheduler)
            return scheduler;
    }

    return make<TimerVSyncScheduler>(move(tick_callback));
}

#elif defined(AK_OS_ANDROID)

// Android hands out the display's vsync through AChoreographer, whose frame callbacks run on a thread with an ALooper.
// That thread waits on its looper, and a request for a tick wakes it through an eventfd to ask for the next frame.
class ChoreographerState final : public AtomicRefCounted<ChoreographerState> {
public:
    ChoreographerState(Core::EventLoop& event_loop, Function<void(MonotonicTime)>&& tick_callback, int wake_fd)
        : event_loop(event_loop)
        , tick_callback(move(tick_callback))
        , wake_fd(wake_fd)
    {
    }

    void wake()
    {
        u64 value = 1;
        (void)::write(wake_fd, &value, sizeof(value));
    }

    Core::EventLoop& event_loop;
    Function<void(MonotonicTime)> tick_callback;
    int wake_fd { -1 };
    Atomic<bool> is_valid { true };
    Atomic<bool> is_stopping { false };
    Atomic<bool> tick_requested { false };
    Atomic<i64> most_recent_tick_time_nanoseconds { 0 };

    // Only used on the choreographer thread.
    AChoreographer* choreographer { nullptr };
    bool frame_callback_is_pending { false };

};

static void choreographer_frame_callback(int64_t, void* data)
{
    auto& state = *static_cast<ChoreographerState*>(data);
    state.frame_callback_is_pending = false;

    // NB: Sample the shared monotonic clock on the choreographer thread so deferred delivery does not shift the frame
    //     timestamp to the Compositor event-loop wakeup time.
    auto frame_time = MonotonicTime::now();
    state.most_recent_tick_time_nanoseconds.store(frame_time.nanoseconds());
    if (!state.tick_requested.exchange(false))
        return;

    state.event_loop.deferred_invoke([state = NonnullRefPtr { state }, frame_time] {
        if (state->is_valid)
            state->tick_callback(frame_time);
    });
}

static int choreographer_wake_callback(int fd, int, void* data)
{
    auto& state = *static_cast<ChoreographerState*>(data);
    u64 value = 0;
    (void)::read(fd, &value, sizeof(value));
    if (state.is_stopping)
        return 0;
    if (state.tick_requested && !state.frame_callback_is_pending) {
        state.frame_callback_is_pending = true;
        AChoreographer_postFrameCallback64(state.choreographer, choreographer_frame_callback, &state);
    }
    return 1;
}

class ChoreographerVSyncScheduler final : public VSyncScheduler {
public:
    AK_ALLOC_WITH_KMALLOC;

    // Hands the callback back if there is no choreographer, such as in a process that cannot reach SurfaceFlinger.
    static OwnPtr<ChoreographerVSyncScheduler> try_create(Function<void(MonotonicTime)>& tick_callback)
    {
        int wake_fd = ::eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
        if (wake_fd < 0)
            return nullptr;

        // The thread says through this whether it found a choreographer: 1 if it did, and 2 if not.
        int startup_fd = ::eventfd(0, EFD_CLOEXEC);
        if (startup_fd < 0) {
            ::close(wake_fd);
            return nullptr;
        }

        auto state = adopt_ref(*new ChoreographerState(Core::EventLoop::current(), move(tick_callback), wake_fd));
        auto thread = Threading::Thread::construct("Choreographer"sv, [state, startup_fd] {
            auto* looper = ALooper_prepare(0);
            state->choreographer = AChoreographer_getInstance();
            u64 startup_result = state->choreographer ? 1 : 2;
            (void)::write(startup_fd, &startup_result, sizeof(startup_result));
            if (!state->choreographer)
                return static_cast<intptr_t>(0);

            ALooper_addFd(looper, state->wake_fd, ALOOPER_POLL_CALLBACK, ALOOPER_EVENT_INPUT, choreographer_wake_callback, state.ptr());
            while (!state->is_stopping)
                ALooper_pollOnce(-1, nullptr, nullptr, nullptr);
            ALooper_removeFd(looper, state->wake_fd);
            return static_cast<intptr_t>(0);
        });
        thread->start();

        u64 startup_result = 0;
        while (::read(startup_fd, &startup_result, sizeof(startup_result)) < 0 && errno == EINTR) {
        }
        ::close(startup_fd);
        if (startup_result != 1) {
            (void)thread->join();
            tick_callback = move(state->tick_callback);
            ::close(wake_fd);
            return nullptr;
        }
        return adopt_own(*new ChoreographerVSyncScheduler(move(state), move(thread)));
    }

    virtual ~ChoreographerVSyncScheduler() override
    {
        m_state->is_valid = false;
        m_state->is_stopping = true;
        m_state->wake();
        (void)m_thread->join();
        ::close(m_state->wake_fd);
    }

    virtual void schedule(double) override
    {
        if (m_state->tick_requested.exchange(true))
            return;
        m_state->wake();
    }

    virtual Optional<MonotonicTime> most_recent_tick_time(MonotonicTime now, double refresh_rate) override
    {
        auto tick_time_nanoseconds = m_state->most_recent_tick_time_nanoseconds.load();
        if (tick_time_nanoseconds == 0 || tick_time_nanoseconds > now.nanoseconds())
            return {};

        auto age_nanoseconds = now.nanoseconds() - tick_time_nanoseconds;
        auto frame_interval_nanoseconds = static_cast<i64>((1'000'000'000.0 / refresh_rate) + 0.5);
        if (age_nanoseconds >= frame_interval_nanoseconds)
            return {};
        return now - AK::Duration::from_nanoseconds(age_nanoseconds);
    }

private:
    ChoreographerVSyncScheduler(NonnullRefPtr<ChoreographerState> state, NonnullRefPtr<Threading::Thread> thread)
        : m_state(move(state))
        , m_thread(move(thread))
    {
    }

    NonnullRefPtr<ChoreographerState> m_state;
    NonnullRefPtr<Threading::Thread> m_thread;
};

OwnPtr<VSyncScheduler> create_vsync_scheduler(Optional<u64>, Function<void(MonotonicTime)>&& tick_callback)
{
    if (auto scheduler = ChoreographerVSyncScheduler::try_create(tick_callback))
        return scheduler;
    dbgln("No AChoreographer, so frames are paced by a timer");
    return make<TimerVSyncScheduler>(move(tick_callback));
}

#else

OwnPtr<VSyncScheduler> create_vsync_scheduler(Optional<u64>, Function<void(MonotonicTime)>&& tick_callback)
{
    return make<TimerVSyncScheduler>(move(tick_callback));
}

#endif

}
