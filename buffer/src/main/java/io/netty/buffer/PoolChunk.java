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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 *
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 */

/***
 * 每个 Arena下面包含多个小块 Chunk
 * 每个Chunk下面默认被分为Page
 * Chunk在Netty中默认为16M
 * Chunk默认情况下被分为
 * 2048个小的Page
 * @param <T>
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    /***
     * 所属的Arena对象
     */
    final PoolArena<T> arena;
    /***
     * 即用于 PooledByteBuf.memory 属性，有 Direct ByteBuffer 和 byte[] 字节数组。
     */
    final T memory;
    /***
     * 是否非池化
     */
    final boolean unpooled;

    /***
     * 这个Chunk在内存的初始地址
     */
    final int offset;
    /***
     * 分配信息满二叉树
     */
    private final byte[] memoryMap;
    /***
     * 高度信息满二叉树
     */
    private final byte[] depthMap;

    /***
     * PoolSubpage数组
     */
    private final PoolSubpage<T>[] subpages;
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    /***
     * 断分配请求内存是否为 Tiny/Small ，即分配 Subpage 内存块。
     */
    private final int subpageOverflowMask;
    /***
     * 这个chunk的大小
     * 默认为8192bit
     */
    private final int pageSize;

    /***
     * 从1开始左移到pageSize的次数
     * 默认移动13次到达8k
     * 因此这个值默认为13
     */
    private final int pageShifts;

    /***
     * 满二叉树的高度。默认为 11 。 层高从0开始
     */
    private final int maxOrder;
    /***
     * chunk内存占用的大小  默认16M=8*2048
     * 及16777216bit
     */
    private final int chunkSize;

    /***
     * log2 {@link #chunkSize} 的结果。默认为 log2( 16M ) = 24 。
     */
    private final int log2ChunkSize;

    /***
     * 可分配 {@link #subpages} 的数量，即数组大小。默认为 1 << maxOrder = 1 << 11 = 2048 。
     * 默认2048 默认Page的个数
     */
    private final int maxSubpageAllocs;
    /** Used to mark memory as unusable */

    /***
     * 标记节点不可用。默认为 maxOrder + 1 = 12 。
     */
    private final byte unusable;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    private final Deque<ByteBuffer> cachedNioBuffers;

    /***
     * 剩余可用的字节数
     */
    private int freeBytes;

    /***
     * 属于的ChunkList对象
     */
    PoolChunkList<T> parent;

    /***
     * 上一个Chunk对象
     */
    PoolChunk<T> prev;

    /***
     * 上一个Chunk对象
     */
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /***
     * 创建一个非池化
     * @param arena
     * @param memory
     * @param pageSize
     * @param maxOrder
     * @param pageShifts
     * @param chunkSize
     * @param offset
     */
    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;
        unusable = (byte) (maxOrder + 1);
        log2ChunkSize = log2(chunkSize);
        subpageOverflowMask = ~(pageSize - 1);
        freeBytes = chunkSize;

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder;

        // Generate the memory map.
        /***
         * maxSubpageAllocs默认时候等于2048
         * maxSubpageAllocs << 1就等于4096
         */
        memoryMap = new byte[maxSubpageAllocs << 1];
        /***
         * memoryMap的大小  默认为4096
         */
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            /***
             * 第一几层 就第几个就有2的n次方个节点
             * 所以需要移位，最后这两个数组都是首先
             * 是两个数组内容一模一样的数组，其次从1-4096
             * 每层的元素的值都是一样的自己看代码理解
             * 反人类的代码不知道死了多少细胞
             */
            int depth = 1 << d;
            /***
             * 1 memoryMap[id] = depthMap[id] -- 该节点没有被分配
             * 2 memoryMap[id] > depthMap[id] -- 至少有一个子节点被分配，
             * 不能再分配该高度满足的内存，但可以根据实际分配较小一些的内存。比如，
             * 上图中分配了4号子节点的2号节点，值从1更新为2，表示该节点不能再分配8MB的只能最大分配4MB内存，
             * 因为分配了4号节点后只剩下5号节点可用。
             * 3 mempryMap[id] = 最大高度 + 1（本例中12） -- 该节点及其子节点已被完全分配， 没有剩余空间。
             *
             */
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                /***
                 * 随着内存的分配会在不停的变化这个数组
                 */
                memoryMap[memoryMapIndex] = (byte) d;
                /***
                 * 初始化之后永远不会在改变了
                 */
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        /***
         * 默认情况下maxSubpageAllocs为2048
         * 及二叉树中最底层的那些节点 因此有2048个哈
         */
        subpages = newSubpageArray(maxSubpageAllocs);
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        final long handle;
        /***
         * 大于等于Page大小 分配Page内存快
         */
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            /***
             * 分配Page
             */
            handle =  allocateRun(normCapacity);
        } else {
            /***
             * 分配SubPage
             */
            handle = allocateSubpage(normCapacity);
        }

        if (handle < 0) {
            return false;
        }
        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;
        initBuf(buf, nioBuffer, handle, reqCapacity);
        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    /***
     * 更新父节点不可用
     * @param id
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            /***
             * 获取父节点编号
             */
            int parentId = id >>> 1;
            /***
             * 获取字节点1的值
             */
            byte val1 = value(id);
            /***
             * 获取字节点2的值
             */
            byte val2 = value(id ^ 1);
            /***
             * 获取子节点较小的值 并设置到父节点
             */
            byte val = val1 < val2 ? val1 : val2;
            setValue(parentId, val);
            /***
             * 跳到父节点继续循环去
             */
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        /***
         * 获取当前节点的子节点的层级
         */
        int logChild = depth(id) + 1;
        while (id > 1) {
            /***
             * 获取父节点的编号
             */
            int parentId = id >>> 1;
            /***
             * 获得子节点的值
             */
            byte val1 = value(id);
            /***
             * 获取另外一个子节点数
             */
            byte val2 = value(id ^ 1);
            /***
             * 获得当前节点的层次
             */
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up
            /***
             * 如果两个节点都可以用  则直接设置父节点的层次
             */
            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                /***
                 * 两个字节点任一个不可用  则取子节点较小值  并设置到父节点
                 */
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            /***
             * 跳到父节点继续循环
             */
            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth
     * @return index in memoryMap
     * /***
     * 获取节点
     */
    private int allocateNode(int d) {
        int id = 1;
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        byte val = value(id);
        if (val > d) { // unusable
            return -1;
        }
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1;
            val = value(id);
            if (val > d) {
                id ^= 1;
                val = value(id);
            }
        }
        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        setValue(id, unusable); // mark as unusable
        updateParentsAlloc(id);
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
        /***
         * 获得层高
         */
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        /***
         * 获得节点
         */
        int id = allocateNode(d);
        /***
         *  未获得到节点，直接返回
         */
        if (id < 0) {
            return id;
        }
        /***
         * 减少剩余可用字节数
         */
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        /***
         * 获得对应内存规格的 Subpage 双向链表的 head 节点
         * 俺也不知道Subpage这个玩意里面是什么数据结构
         * 俺也不敢问 默默接受吧亲
         */
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        /***
         * 获得最底层的一个节点  Subpage只能使用二叉树的最底层的节点，
         * 为什么你自己想  还需要问吗
         */
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
        synchronized (head) {
            int id = allocateNode(d);
            /***
             * 获取失败你就直接返回撒 我也没有办法
             */
            if (id < 0) {
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;

            /***
             * 减少剩余可用字节数
             */
            freeBytes -= pageSize;

            /***
             * 获得节点对应的subpages数组的编号
             */
            int subpageIdx = subpageIdx(id);
            /***
             * 获得节点对应的 subpages 数组的 PoolSubpage 对象
             */
            PoolSubpage<T> subpage = subpages[subpageIdx];
            /***
             * 初始化 PoolSubpage 对象
             */
            if (subpage == null) {
                /***
                 *  不存在，则进行创建 PoolSubpage 对象
                 */
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                /***
                 * / 存在，则重新初始化 PoolSubpage 对象
                 */
                subpage.init(head, normCapacity);
            }
            /***
             * // 分配 PoolSubpage 内存块
             */
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    /***
     * 释放到内存快
     * 可能是SubPage
     * 也可能是Page
     * @param handle
     * @param nioBuffer
     */
    void free(long handle, ByteBuffer nioBuffer) {
        /***
         * 获得memoryMap的数组的编号（下标）
         */
        int memoryMapIdx = memoryMapIdx(handle);
        /***
         * 获得bitmap数组的编号( 下标 )。注意，此时获得的还不是真正的 bitmapIdx 值，需要经过 `bitmapIdx & 0x3FFFFFFF` 运算。
         */
        int bitmapIdx = bitmapIdx(handle);

        /***
         * 释放subPage
         */
        if (bitmapIdx != 0) { // free a subpage
            /***
             * 获取PoolSubpage对象
             */
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            /***
             * 获的对应内存规格的Subpage双向链标的head节点
             */
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                /***
                 * 释放subpage
                 */
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }
        /***
         * 添加可用内存的数值
         */
        freeBytes += runLength(memoryMapIdx);
        /***
         * 设置page对应的的节点可用
         */
        setValue(memoryMapIdx, depth(memoryMapIdx));
        /***
         * 更新page对应的节点的祖先可用
         */
        updateParentsFree(memoryMapIdx);

        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    /***
     * 初始化分配的内存快到PooledByteBuf中
     * @param buf
     * @param nioBuffer
     * @param handle
     * @param reqCapacity
     */
    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        /***
         * 获取memoryMap数组的标号（下标）
         */
        int memoryMapIdx = memoryMapIdx(handle);
        /***
         * 获得 bitmap 数组的编号( 下标 )。注意，此时获得的还不是真正的 bitmapIdx 值，需要经过 `bitmapIdx & 0x3FFFFFFF` 运算
         */
        int bitmapIdx = bitmapIdx(handle);
        /***
         * 内存块为 Page
         */
        if (bitmapIdx == 0) {
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            /***
             * 初始化Page 内存快到PooledByteBuf中了
             */
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset,
                    reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache());
        }
        else {
            /***
             * 内存为SubPage  初始化SubPage到PooledByteBuf中了
             */
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle);
        /***
         *  // 获得 memoryMap 数组的编号( 下标 )
         */
        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        /***
         * 初始化 SubPage 内存块到 PooledByteBuf 中
         */
        buf.init(
            this, nioBuffer, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id);
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
