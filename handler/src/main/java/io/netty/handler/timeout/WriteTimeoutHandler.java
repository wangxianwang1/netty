/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.timeout;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Raises a {@link WriteTimeoutException} when a write operation cannot finish in a certain period of time.
 *
 * <pre>
 * // The connection is closed when a write operation cannot finish in 30 seconds.
 *
 * public class MyChannelInitializer extends {@link ChannelInitializer}&lt;{@link Channel}&gt; {
 *     public void initChannel({@link Channel} channel) {
 *         channel.pipeline().addLast("writeTimeoutHandler", new {@link WriteTimeoutHandler}(30);
 *         channel.pipeline().addLast("myHandler", new MyHandler());
 *     }
 * }
 *
 * // Handler should handle the {@link WriteTimeoutException}.
 * public class MyHandler extends {@link ChannelDuplexHandler} {
 *     {@code @Override}
 *     public void exceptionCaught({@link ChannelHandlerContext} ctx, {@link Throwable} cause)
 *             throws {@link Exception} {
 *         if (cause instanceof {@link WriteTimeoutException}) {
 *             // do something
 *         } else {
 *             super.exceptionCaught(ctx, cause);
 *         }
 *     }
 * }
 *
 * {@link ServerBootstrap} bootstrap = ...;
 * ...
 * bootstrap.childHandler(new MyChannelInitializer());
 * ...
 * </pre>
 * @see ReadTimeoutHandler
 * @see IdleStateHandler
 */
public class WriteTimeoutHandler extends ChannelOutboundHandlerAdapter {
    //最小的超时时间
    private static final long MIN_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    //超时时间
    private final long timeoutNanos;

    /**
     * A doubly-linked list to track all WriteTimeoutTasks
     */
    /***
     *  WriteTimeoutTask 双向链表。
     *  lastTask 为链表的尾节点
     */
    private WriteTimeoutTask lastTask;

    /***
     * 是否关闭
     */
    private boolean closed;

    /**
     * Creates a new instance.
     *
     * @param timeoutSeconds
     *        write timeout in seconds
     */
    public WriteTimeoutHandler(int timeoutSeconds) {
        this(timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * Creates a new instance.
     *
     * @param timeout
     *        write timeout
     * @param unit
     *        the {@link TimeUnit} of {@code timeout}
     */
    public WriteTimeoutHandler(long timeout, TimeUnit unit) {
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        if (timeout <= 0) {
            timeoutNanos = 0;
        } else {
            timeoutNanos = Math.max(unit.toNanos(timeout), MIN_TIMEOUT_NANOS);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (timeoutNanos > 0) {
            // 如果 promise 是 VoidPromise ，则包装成非 VoidPromise ，为了后续的回调。
            promise = promise.unvoid();
            // 创建定时任务
            scheduleTimeout(ctx, promise);
        }
        //写入
        ctx.write(msg, promise);
    }

    /***
     * 移除所有的WriteTimeoutTask任务  并取消
     * @param ctx
     * @throws Exception
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        WriteTimeoutTask task = lastTask;
        lastTask = null;
        while (task != null) {
            task.scheduledFuture.cancel(false);
            WriteTimeoutTask prev = task.prev;
            task.prev = null;
            task.next = null;
            task = prev;
        }
    }

    private void scheduleTimeout(final ChannelHandlerContext ctx, final ChannelPromise promise) {
        // Schedule a timeout.
        //创建WriteTimeoutTask定时任务
        final WriteTimeoutTask task = new WriteTimeoutTask(ctx, promise);
        //定时任务
        task.scheduledFuture = ctx.executor().schedule(task, timeoutNanos, TimeUnit.NANOSECONDS);

        if (!task.scheduledFuture.isDone()) {
            //添加定时任务
            addWriteTimeoutTask(task);

            // Cancel the scheduled timeout if the flush promise is complete.
           //将task作为监听器 添加到promise中 在写入完成之后，可以移除定时任务
            promise.addListener(task);
        }
    }

    /***
     * 添加到链表的尾节点  并修改lastTask尾当前任务
     * @param task
     */
    private void addWriteTimeoutTask(WriteTimeoutTask task) {
        if (lastTask != null) {
            lastTask.next = task;
            task.prev = lastTask;
        }
        lastTask = task;
    }

    private void removeWriteTimeoutTask(WriteTimeoutTask task) {
        //从双向链接
        if (task == lastTask) {
            // task is the tail of list
            assert task.next == null;
            lastTask = lastTask.prev;
            if (lastTask != null) {
                lastTask.next = null;
            }
        } else if (task.prev == null && task.next == null) {
            // Since task is not lastTask, then it has been removed or not been added.
            return;
        } else if (task.prev == null) {
            // task is the head of list and the list has at least 2 nodes
            task.next.prev = null;
        } else {
            task.prev.next = task.next;
            task.next.prev = task.prev;
        }
        task.prev = null;
        task.next = null;
    }

    /**
     * Is called when a write timeout was detected
     */
    protected void writeTimedOut(ChannelHandlerContext ctx) throws Exception {
        if (!closed) {
            ctx.fireExceptionCaught(WriteTimeoutException.INSTANCE);
            ctx.close();
            closed = true;
        }
    }

    private final class WriteTimeoutTask implements Runnable, ChannelFutureListener {

        private final ChannelHandlerContext ctx;
        private final ChannelPromise promise;

        // WriteTimeoutTask is also a node of a doubly-linked list
        WriteTimeoutTask prev;
        WriteTimeoutTask next;

        ScheduledFuture<?> scheduledFuture;

        WriteTimeoutTask(ChannelHandlerContext ctx, ChannelPromise promise) {
            this.ctx = ctx;
            this.promise = promise;
        }

        @Override
        public void run() {
            // Was not written yet so issue a write timeout
            // The promise itself will be failed with a ClosedChannelException once the close() was issued
            // See https://github.com/netty/netty/issues/2159
            if (!promise.isDone()) {   //未完成 说明写入超时
                try {
                    writeTimedOut(ctx);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            }
            //<2> 移除出链表
            removeWriteTimeoutTask(this);
        }

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            // scheduledFuture has already be set when reaching here
            scheduledFuture.cancel(false);
            removeWriteTimeoutTask(this);
        }
    }
}
