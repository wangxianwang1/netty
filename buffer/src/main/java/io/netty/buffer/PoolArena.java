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

package io.netty.buffer;

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;
import static java.lang.Math.max;

abstract class PoolArena<T> implements PoolArenaMetric {
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    enum SizeClass {
        Tiny,
        Small,
        Normal
        /***
         * 其实还有一个隐藏的 Huge
         */
    }

    /***
     * tinySubpagePools数组的大小
     * 默认为32
     */
    static final int numTinySubpagePools = 512 >>> 4;

    /***
     * 所属 PooledByteBufAllocator 对象
     */
    final PooledByteBufAllocator parent;

    /***
     * 满二叉树的高度  默认为11
     */
    private final int maxOrder;

    /***
     * Page大小  默认8k=8192b
     */
    final int pageSize;
    /***
     * 从1 开始左移到pageSize值时候的位数
     * 默认为13  1<<13=8192
     */
    final int pageShifts;

    /***
     * Chunk内存快占用大小
     * 默认16M=16*1024
     */
    final int chunkSize;
    /***
     * 判断分配的内存是否为Tiny/Small ，即分配 Subpage 内存块。
     */
    final int subpageOverflowMask;

    /***
     * smallSubpagePools数组的大小
     * 默认为23
     */
    final int numSmallSubpagePools;

    /***
     * 对齐基准
     * 默认为0  可以忽略哈
     */
    final int directMemoryCacheAlignment;
    /***
     * 掩码
     * 默认为-1 可以忽略哈
     */
    final int directMemoryCacheAlignmentMask;

    /***
     * tiny 类型的 PoolSubpage 数组
     * 数组的每个元素，都是双向链表
     */
    private final PoolSubpage<T>[] tinySubpagePools;
    /***
     * small 类型的 SubpagePools 数组
     *  数组的每个元素，都是双向链表
     */
    private final PoolSubpage<T>[] smallSubpagePools;

    /***
     * PoolChunkList之间的双向链表
     */
    private final PoolChunkList<T> q050;
    private final PoolChunkList<T> q025;
    private final PoolChunkList<T> q000;
    private final PoolChunkList<T> qInit;
    private final PoolChunkList<T> q075;
    private final PoolChunkList<T> q100;

    /***
     * PoolChunkListMetric 数组
     */
    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations
    /***
     * 分配Normal内存快的次数
     */
    private long allocationsNormal;
    // We need to use the LongCounter here as this is not guarded via synchronized block.
    /***
     * 分配Tiny内存快的次数
     */
    private final LongCounter allocationsTiny = PlatformDependent.newLongCounter();
    /***
     * 分配Small内存块的次数
     */
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    /***
     * 分配Huge内存块的次数
     */
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    /****
     * 正在使用的Huge内存块的总共占用字节数
     */
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    /***
     * 释放Tiny内存块的次数
     */
    private long deallocationsTiny;
    /***
     * 释放Small内存块的次数
     */
    private long deallocationsSmall;

    /***
     * 释放Normal内存块的次数
     */
    private long deallocationsNormal;

    // We need to use the LongCounter here as this is not guarded via synchronized block.
    /***
     * 释放Huge内存块的次数
     */
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    // Number of thread caches backed by this arena.
    /***
     * 该PoolArena被多少线程引用的计数器
     */
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
          int maxOrder, int pageShifts, int chunkSize, int cacheAlignment) {
        this.parent = parent;
        this.pageSize = pageSize;
        this.maxOrder = maxOrder;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        directMemoryCacheAlignment = cacheAlignment;
        directMemoryCacheAlignmentMask = cacheAlignment - 1;
        subpageOverflowMask = ~(pageSize - 1);
        /***
         * 初始化 tinySubpagePools 数组
         * 默认是32
         */
        tinySubpagePools = newSubpagePoolArray(numTinySubpagePools);
        for (int i = 0; i < tinySubpagePools.length; i ++) {
            tinySubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        /***
         * 初始化 smallSubpagePools 数组
         * 默认是 13-9=4
         */
        numSmallSubpagePools = pageShifts - 9;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            smallSubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        /***
         * PoolChunkList 之间的双向链表，初始化
         * 正向qInit -> q000 -> q025 -> q050 -> q075 -> q100 -> null
         */
        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        /***
         * 1 null <- q000 <- q025 <- q050 <- q075 <- q100
         * qInit <- qInit
         * 2 比较神奇的是qInit指向自己 qInit用途是新创建的Chunk内存快 添加到qInit后 不会被释放掉
         * 3 为什么不会被释放掉qInit.minUsage = Integer.MIN_VALUE ，所以在 PoolChunkList#move(PoolChunk chunk) 方法中，
         *  chunk_new 的内存使用率最小值为 0 ，所以肯定不会被释放。
         *4 那岂不是 chunk_new 无法被释放？随着 chunk_new 逐渐分配内存，内存使用率到达 25 ( qInit.maxUsage )后，会移动到 q000 。
         *  再随着 chunk_new 逐渐释放内存，内存使用率降到 0 (q000.minUsage) 后，就可以被释放。
         *5 当然，如果新创建的 Chunk 内存块 chunk_new 第一次分配的内存使用率超过 25 ( qInit.maxUsage )，
         *  不会进入 qInit 中，而是进入后面的 PoolChunkList 节点
         */
        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);
        qInit.prevList(qInit);

        /***
         * 创建PoolChunkListMetric数组
         */
        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    private PoolSubpage<T> newSubpagePoolHead(int pageSize) {
        /***
         * 先不管new一个PoolSubpage当作head节点
         * 这个head节点的上一个节点和小一个节点都是
         * head节点  因此他是一个环链
         */
        PoolSubpage<T> head = new PoolSubpage<T>(pageSize);
        head.prev = head;
        head.next = head;
        return head;
    }

    /***
     * 创建PoolSubpage数组对象
     * 数组大小为size
     * @param size
     * @return
     */
    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    /***
     * 请求分配的内存大小在tinySubpagePools的数组的下标
     * @param normCapacity
     * @return
     */
    static int tinyIdx(int normCapacity) {
        return normCapacity >>> 4;
    }

    /***
     * 计算请求分配的内存大小在 smallSubpagePools 数组的下标
     * @param normCapacity
     * @return
     */
    static int smallIdx(int normCapacity) {
        int tableIdx = 0;
        int i = normCapacity >>> 10;
        while (i != 0) {
            i >>>= 1;
            tableIdx ++;
        }
        return tableIdx;
    }

    /***
     * 判断是否小形或者微小形
     * 只要申请的容量normCapacity小于pageSize即可
     * @param normCapacity
     * @return
     */
    // capacity < pageSize
    boolean isTinyOrSmall(int normCapacity) {
        return (normCapacity & subpageOverflowMask) == 0;
    }

    /***
     * 小于512bit就是小型
     * @param normCapacity
     * @return
     */
    // normCapacity < 512
    static boolean isTiny(int normCapacity) {
        return (normCapacity & 0xFFFFFE00) == 0;
    }


    /***
     * 根据申请分配的内存大小不同
     * 提供两种方式进行分配内存
     * PoolSubpage ，用于分配小于 8KB 的内存块
     *    tinySubpagePools 属性，用于分配小于 512B 的 tiny Subpage 内存块。
     *    smallSubpagePools 属性，用于分配小于 8KB 的 small Subpage 内存块。
     * PoolChunkList ，用于分配大于等于 8KB 的内存块
     *     小于 32MB ，分配 normal 内存块，即一个 Chunk 中的 Page 内存块。
     *     大于等于 32MB ，分配 huge 内存块，即一整个 Chunk 内存块。
     * @param cache
     * @param buf
     * @param reqCapacity
     */
    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        /***
         * 标准化请求分配的内存的容量
         */
        final int normCapacity = normalizeCapacity(reqCapacity);
        /***
         * tiny或者small类型的
         */
        if (isTinyOrSmall(normCapacity)) { // capacity < pageSize
            int tableIdx;
            PoolSubpage<T>[] table;
            boolean tiny = isTiny(normCapacity);
            /***
             * tiny类型
             */
            if (tiny) { // < 512
                /***
                 * 从PoolThreadCache缓存中
                 * 分配tiny内存快，并初始化到
                 * PooledByteBuf中
                 */
                if (cache.allocateTiny(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                /***
                 * 获得tableId和table属性
                 */
                tableIdx = tinyIdx(normCapacity);
                table = tinySubpagePools;
            }
            /***
             * small类型
             */
            else {
                /***
                 * 从 PoolThreadCache 缓存中，分配 small 内存块，并初始化到 PooledByteBuf 中。
                 */
                if (cache.allocateSmall(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                tableIdx = smallIdx(normCapacity);
                table = smallSubpagePools;
            }

            /***
             * 获取PoolSubpage链表的头节点
             */
            final PoolSubpage<T> head = table[tableIdx];

            /**
             * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
             * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
             */
            synchronized (head) {
                final PoolSubpage<T> s = head.next;
                if (s != head) {
                    assert s.doNotDestroy && s.elemSize == normCapacity;
                    /***
                     * 分配Subpage内存块
                     */
                    long handle = s.allocate();
                    assert handle >= 0;
                    /***
                     * 初始化 Subpage 内存块到 PooledByteBuf 对象中
                     */
                    s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity);
                    /***
                     *  allocationsTiny 或 allocationsSmall 计数
                     */
                    incTinySmallAllocation(tiny);
                    return;
                }
            }
            /***
             * 申请Normal page内存块
             * 实际上，只占用其中一块 Subpage 内存块。
             */
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
            }

            /***
             * 增加 allocationsTiny 或 allocationsSmall 计数
             */
            incTinySmallAllocation(tiny);
            return;
        }
        if (normCapacity <= chunkSize) {
            /***
             * 从 PoolThreadCache 缓存中，分配 normal 内存块，并初始化到 PooledByteBuf 中
             */
            if (cache.allocateNormal(this, buf, reqCapacity, normCapacity)) {
                // was able to allocate out of the cache so move on
                return;
            }
            synchronized (this) {
                /***
                 * 申请 Normal Page 内存块
                 */
                allocateNormal(buf, reqCapacity, normCapacity);
                ++allocationsNormal;
            }
        } else {
            // Huge allocations are never served via the cache so just call allocateHuge
            /***
             * 申请Huge Page 内存块
             */
            allocateHuge(buf, reqCapacity);
        }
    }

    // Method must be called inside synchronized(this) { ... } block
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        /***
         * 按照优先级 从多个ChunkList中
         * 分配 Normal Page 内存块。如果有一分配成功，返回
         */
        if (q050.allocate(buf, reqCapacity, normCapacity) || q025.allocate(buf, reqCapacity, normCapacity) ||
            q000.allocate(buf, reqCapacity, normCapacity) || qInit.allocate(buf, reqCapacity, normCapacity) ||
            q075.allocate(buf, reqCapacity, normCapacity)) {
            return;
        }

        // Add a new chunk.
        /***
         * 还是没有分配成功
         * 只能新建一个Chunk 内存块
         */
        PoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize);
        boolean success = c.allocate(buf, reqCapacity, normCapacity);
        assert success;
        qInit.add(c);
    }

    private void incTinySmallAllocation(boolean tiny) {
        if (tiny) {
            allocationsTiny.increment();
        } else {
            allocationsSmall.increment();
        }
    }

    /***
     * 分配大内存的
     * @param buf
     * @param reqCapacity
     */
    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        activeBytesHuge.add(chunk.chunkSize());
        buf.initUnpooled(chunk, reqCapacity);
        allocationsHuge.increment();
    }

    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {
        if (chunk.unpooled) {
            int size = chunk.chunkSize();
            /***
             * 直接销毁 Chunk 内存块，因为占用空间较大
             */
            destroyChunk(chunk);
            /***
             * 减少 activeBytesHuge 计数
             */
            activeBytesHuge.add(-size);
            /***
             * 减少 deallocationsHuge 计数
             */
            deallocationsHuge.increment();
        } else {
            SizeClass sizeClass = sizeClass(normCapacity);
            /***
             * 添加内存块到 PoolThreadCache 缓存
             */
            if (cache != null && cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                return;
            }

            /***
             * 释放 Page / Subpage 内存块回 Chunk 中
             */
            freeChunk(chunk, handle, sizeClass, nioBuffer);
        }
    }

    /***
     * 申请内存的根据内存大小的类型
     * @param normCapacity
     * @return
     */
    private SizeClass sizeClass(int normCapacity) {
        if (!isTinyOrSmall(normCapacity)) {
            return SizeClass.Normal;
        }
        return isTiny(normCapacity) ? SizeClass.Tiny : SizeClass.Small;
    }

    void freeChunk(PoolChunk<T> chunk, long handle, SizeClass sizeClass, ByteBuffer nioBuffer) {
        final boolean destroyChunk;
        synchronized (this) {
            switch (sizeClass) {
            case Normal:
                ++deallocationsNormal;
                break;
            case Small:
                ++deallocationsSmall;
                break;
            case Tiny:
                ++deallocationsTiny;
                break;
            default:
                throw new Error();
            }
            destroyChunk = !chunk.parent.free(chunk, handle, nioBuffer);
        }
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    PoolSubpage<T> findSubpagePoolHead(int elemSize) {
        int tableIdx;
        PoolSubpage<T>[] table;
        /***
         * 小于512
         */
        if (isTiny(elemSize)) { // < 512
            tableIdx = elemSize >>> 4;
            /***
             * 获取table
             */
            table = tinySubpagePools;
        } else {
            tableIdx = 0;
            elemSize >>>= 10;
            while (elemSize != 0) {
                elemSize >>>= 1;
                tableIdx ++;
            }
            table = smallSubpagePools;
        }
        /***
         * 获取Subpage链表的头节点
         */
        return table[tableIdx];
    }

    /***
     * 标准化请求分配的内存大小
     * 通过这样的方式
     * 保证分配的内存块
     * @param reqCapacity
     * @return
     */
    /***
     * 对内存块大小的一个说明哈
     * 小于等于496bit时候是tiny 16bit形式增加
     * 大于496bit而且小于等于4k是samll类型  2倍的形式增加
     * 大于等于8k时候小于等于16M时候是normal类型  2倍的形式增加
     * 大于16m时候  huge形  无规律新增
     * @param reqCapacity
     * @return
     */
    int normalizeCapacity(int reqCapacity) {
        checkPositiveOrZero(reqCapacity, "reqCapacity");

        /***
         * 如果申请的内存的大小大于chunk的大小
         * Huge 内存类型 直接使用reqCapacity
         * 不需要进行标准化 个人觉得真正需要
         * 这么大的内存的case的请求应该不多吧
         */
        if (reqCapacity >= chunkSize) {
            /***
             * 默认情况下directMemoryCacheAlignment==0
             * 所以直接返回reqCapacity对象
             */
            return directMemoryCacheAlignment == 0 ? reqCapacity : alignCapacity(reqCapacity);
        }

        /***
         * 非tiny内存类型 及大于512
         * 及small和normal类型
         */
        if (!isTiny(reqCapacity)) { // >= 512
            // Doubled

            /***
             * 转化成接近于两倍的容量
             */
            int normalizedCapacity = reqCapacity;
            normalizedCapacity --;
            normalizedCapacity |= normalizedCapacity >>>  1;
            normalizedCapacity |= normalizedCapacity >>>  2;
            normalizedCapacity |= normalizedCapacity >>>  4;
            normalizedCapacity |= normalizedCapacity >>>  8;
            normalizedCapacity |= normalizedCapacity >>> 16;
            normalizedCapacity ++;

            if (normalizedCapacity < 0) {
                normalizedCapacity >>>= 1;
            }
            assert directMemoryCacheAlignment == 0 || (normalizedCapacity & directMemoryCacheAlignmentMask) == 0;

            return normalizedCapacity;
        }

        if (directMemoryCacheAlignment > 0) {
            return alignCapacity(reqCapacity);
        }

        /***
         * tiny内心
         * 补齐成16的倍数吧
         */
        // Quantum-spaced
        if ((reqCapacity & 15) == 0) {
            return reqCapacity;
        }

        return (reqCapacity & ~15) + 16;
    }

    /***
     * 对齐请求分配的内存大小
     * @param reqCapacity
     * @return
     */
    int alignCapacity(int reqCapacity) {
        /***
         * 获取delta
         */
        int delta = reqCapacity & directMemoryCacheAlignmentMask;
        /***
         * 补齐 directMemoryCacheAlignment ，并减去 delta
         */
        return delta == 0 ? reqCapacity : reqCapacity + directMemoryCacheAlignment - delta;
    }

    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        if (newCapacity < 0 || newCapacity > buf.maxCapacity()) {
            throw new IllegalArgumentException("newCapacity: " + newCapacity);
        }

        /***
         * 容量大小没有变化
         * 直接返回
         */
        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        /***
         * 记录老的内存块的信息
         */
        PoolChunk<T> oldChunk = buf.chunk;
        ByteBuffer oldNioBuffer = buf.tmpNioBuf;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;
        /***
         * 进来读写索引
         */
        int readerIndex = buf.readerIndex();
        int writerIndex = buf.writerIndex();

        /***
         * 分配新的内存块给PooledByteBuf 对象
         */
        allocate(parent.threadCache(), buf, newCapacity);
        /***
         * 扩容
         */
        if (newCapacity > oldCapacity) {
            /***
             * 将老的内存块的数据，复制到新的内存块中
             */
            memoryCopy(
                    oldMemory, oldOffset,
                    buf.memory, buf.offset, oldCapacity);
        }
        /***
         *缩容
         */
        else if (newCapacity < oldCapacity) {
            /***
             * 有部分数据未读取完
             */
            if (readerIndex < newCapacity) {
                if (writerIndex > newCapacity) {
                    writerIndex = newCapacity;
                }
                /***
                 *  将老的内存块的数据，复制到新的内存块中
                 */
                memoryCopy(
                        oldMemory, oldOffset + readerIndex,
                        buf.memory, buf.offset + readerIndex, writerIndex - readerIndex);
            } else {
                readerIndex = writerIndex = newCapacity;
            }
        }

        /***
         * 设置读写索引
         */
        buf.setIndex(readerIndex, writerIndex);

        /***
         * 释放老的内存块
         */
        if (freeOldMemory) {
            free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return tinySubpagePools.length;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return subPageMetricList(tinySubpagePools);
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (;;) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsTiny.value() + allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return allocationsTiny.value();
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsTiny + deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public synchronized long numTinyDeallocations() {
        return deallocationsTiny;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsTiny.value() + allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsTiny + deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return max(numTinyAllocations() - numTinyDeallocations(), 0);
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    protected abstract PoolChunk<T> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize);
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);
    protected abstract void memoryCopy(T src, int srcOffset, T dst, int dstOffset, int length);
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
            .append("Chunk(s) at 0~25%:")
            .append(StringUtil.NEWLINE)
            .append(qInit)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 0~50%:")
            .append(StringUtil.NEWLINE)
            .append(q000)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 25~75%:")
            .append(StringUtil.NEWLINE)
            .append(q025)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 50~100%:")
            .append(StringUtil.NEWLINE)
            .append(q050)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 75~100%:")
            .append(StringUtil.NEWLINE)
            .append(q075)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 100%:")
            .append(StringUtil.NEWLINE)
            .append(q100)
            .append(StringUtil.NEWLINE)
            .append("tiny subpages:");
        appendPoolSubPages(buf, tinySubpagePools);
        buf.append(StringUtil.NEWLINE)
           .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (;;) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolSubPages(tinySubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(this, newByteArray(chunkSize), pageSize, maxOrder, pageShifts, chunkSize, 0);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, newByteArray(capacity), capacity, 0);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, byte[] dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst, dstOffset, length);
        }
    }

    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        // mark as package-private, only for unit test
        int offsetCacheLine(ByteBuffer memory) {
            // We can only calculate the offset if Unsafe is present as otherwise directBufferAddress(...) will
            // throw an NPE.
            int remainder = HAS_UNSAFE
                    ? (int) (PlatformDependent.directBufferAddress(memory) & directMemoryCacheAlignmentMask)
                    : 0;

            // offset = alignment - address & (alignment - 1)
            return directMemoryCacheAlignment - remainder;
        }

        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxOrder,
                int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(chunkSize), pageSize, maxOrder,
                        pageShifts, chunkSize, 0);
            }
            final ByteBuffer memory = allocateDirect(chunkSize
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, pageSize,
                    maxOrder, pageShifts, chunkSize,
                    offsetCacheLine(memory));
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(capacity), capacity, 0);
            }
            final ByteBuffer memory = allocateDirect(capacity
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, capacity,
                    offsetCacheLine(memory));
        }

        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner(chunk.memory);
            } else {
                PlatformDependent.freeDirectBuffer(chunk.memory);
            }
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, ByteBuffer dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dst) + dstOffset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                dst = dst.duplicate();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstOffset);
                dst.put(src);
            }
        }
    }
}
