/**
 * 线程模型（整个后端唯一的并发底座）。
 *
 * <h2>为什么需要它</h2>
 * 这个后端同时要干四件互相阻塞的事：转发 WS 帧、合成 TTS、调 LLM、读写记忆/存档。
 * 朴素写法是「谁收到请求谁开线程」，结果就是 §2.4 的 {@code blockId} 错位、
 * 台词同一句上屏两次、ack 回报串到别的句子上——这些在 Node 版里都真实发生过。
 *
 * <h2>三条规则</h2>
 * <ol>
 *   <li><b>一个会话一个 {@link com.nori.backend.runtime.SessionLoop}</b>：会话内的状态变更、
 *       帧下发、队列推进全部排队执行，顺序由入队顺序唯一决定，不靠锁。</li>
 *   <li><b>loop 之间只通过 {@link com.nori.backend.runtime.Mailbox} 通信</b>：
 *       不共享可变对象；跨 loop 需要同步等结果时用 {@code CompletableFuture}，
 *       但绝不在 loop 内部同步等同一个 loop（会自锁）。</li>
 *   <li><b>阻塞式 IO 直接写在虚拟线程里</b>：TTS/LLM/磁盘都是阻塞调用，
 *       用 Thread.ofVirtual 让「同步写法 + 高并发」同时成立，不引入响应式回调链。</li>
 * </ol>
 *
 * <h2>与前端契约的关系</h2>
 * 上行帧、下行帧、ack、TTS 分块都串行穿过同一个 loop，这是「帧序与 blockId 不错位」
 * 的唯一保证；任何绕过 loop 直接写 socket 的代码都视为破坏契约。
 *
 * <h2>和 HANDOFF §6 的对应</h2>
 * 「qid 进程内单调」「9s 兜底」「内容级去重 2.5s」这些行为都依赖「单线程内可见的时序」，
 * 放在 loop 里天然成立；一旦并发就全都要重新论证。
 */
package com.nori.backend.runtime;
