package com.practice.cliproxy.resilience;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 熔断器（circuit breaker）—— 每个上游 provider 一个独立熔断器。Node
 * {@code src/breaker.js} / Python {@code breaker.py} 的等价实现。
 *
 * <p>故障转移时不能一直猛打一个已经挂掉的 provider。熔断器记住最近的失败次数，
 * 超过阈值就「打开（OPEN）」——在冷却期内让请求立即跳到下一个 provider。冷却
 * 结束后进入 HALF_OPEN，放有限个「探测」请求过去测试是否恢复。
 *
 * <pre>
 *   CLOSED    --failures &gt;= threshold--&gt;  OPEN
 *   OPEN      --cooldown elapsed-------&gt;  HALF_OPEN  (放几个探测)
 *   HALF_OPEN --probe succeeds--------&gt;  CLOSED     (复位)
 *   HALF_OPEN --probe fails-----------&gt;  OPEN       (重启冷却)
 * </pre>
 *
 * 刻意避免两个坑：
 * <ol>
 *   <li>请求结束必须释放 half-open 探测名额（无论成败），否则名额泄漏，provider
 *       永远不再可探测。</li>
 *   <li>「客户端请求不兼容」不是 provider 的错，不能计入失败——{@link #recordNeutral}
 *       只释放名额、不动健康计数。</li>
 * </ol>
 */
public class BreakerRegistry {

    public static final String CLOSED = "closed";
    public static final String OPEN = "open";
    public static final String HALF_OPEN = "half_open";

    /** 一次 {@link #canRequest} 的结果：是否放行、是否占用了一个 half-open 探测名额。 */
    public static final class Permit {
        public final boolean allowed;
        public final boolean halfOpen;

        public Permit(boolean allowed, boolean halfOpen) {
            this.allowed = allowed;
            this.halfOpen = halfOpen;
        }
    }

    /** 只读快照（给 UI / 测试）。 */
    public static final class State {
        public final String state;
        public final int failures;

        public State(String state, int failures) {
            this.state = state;
            this.failures = failures;
        }
    }

    private final class Breaker {
        String state = CLOSED;
        int failures = 0;
        long openedAtMs = 0L;
        int halfOpenInFlight = 0;

        Permit canRequest(long nowMs) {
            if (CLOSED.equals(state)) {
                return new Permit(true, false);
            }
            if (OPEN.equals(state)) {
                if (nowMs - openedAtMs >= cooldownMs) {
                    state = HALF_OPEN;
                    halfOpenInFlight = 0;
                } else {
                    return new Permit(false, false);
                }
            }
            // HALF_OPEN：只放行有限个探测。
            if (halfOpenInFlight < halfOpenMax) {
                halfOpenInFlight++;
                return new Permit(true, true);
            }
            return new Permit(false, false);
        }

        void release(boolean halfOpen) {
            if (halfOpen && halfOpenInFlight > 0) {
                halfOpenInFlight--;
            }
        }

        void recordSuccess(boolean halfOpen) {
            release(halfOpen);
            state = CLOSED;
            failures = 0;
            openedAtMs = 0L;
        }

        void recordFailure(boolean halfOpen, long nowMs) {
            release(halfOpen);
            if (HALF_OPEN.equals(state)) {
                state = OPEN;
                openedAtMs = nowMs;
                return;
            }
            failures++;
            if (failures >= failureThreshold) {
                state = OPEN;
                openedAtMs = nowMs;
            }
        }

        void recordNeutral(boolean halfOpen) {
            release(halfOpen);
        }
    }

    // 可视化配置「保存即生效」时这些阈值可被 reconfigure 更新（现存 Breaker 实例直接读
    // 外层字段，所以无需丢弃已累计的健康状态）。
    private volatile int failureThreshold;
    private volatile long cooldownMs;
    private volatile int halfOpenMax;
    private final LongSupplier nowMs;
    private final Map<String, Breaker> breakers = new LinkedHashMap<>();

    public BreakerRegistry(int failureThreshold, long cooldownMs, int halfOpenMax) {
        this(failureThreshold, cooldownMs, halfOpenMax, System::currentTimeMillis);
    }

    public BreakerRegistry(int failureThreshold, long cooldownMs, int halfOpenMax, LongSupplier nowMs) {
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
        this.halfOpenMax = halfOpenMax;
        this.nowMs = nowMs;
    }

    /** 热更新熔断阈值（可视化配置保存后调用）；不影响各 provider 已累计的失败状态。 */
    public synchronized void reconfigure(int failureThreshold, long cooldownMs, int halfOpenMax) {
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
        this.halfOpenMax = halfOpenMax;
    }

    private synchronized Breaker get(String id) {
        return breakers.computeIfAbsent(id, k -> new Breaker());
    }

    public synchronized Permit canRequest(String id) {
        return get(id).canRequest(nowMs.getAsLong());
    }

    public synchronized void recordSuccess(String id, boolean halfOpen) {
        get(id).recordSuccess(halfOpen);
    }

    public synchronized void recordFailure(String id, boolean halfOpen) {
        get(id).recordFailure(halfOpen, nowMs.getAsLong());
    }

    public synchronized void recordNeutral(String id, boolean halfOpen) {
        get(id).recordNeutral(halfOpen);
    }

    public synchronized Map<String, State> snapshot() {
        Map<String, State> out = new LinkedHashMap<>();
        for (Map.Entry<String, Breaker> e : breakers.entrySet()) {
            out.put(e.getKey(), new State(e.getValue().state, e.getValue().failures));
        }
        return out;
    }
}
