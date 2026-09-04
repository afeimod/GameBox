/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017 Nikita Shakarun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.lcdui.event;

import android.util.Log;

import javax.microedition.util.LinkedList;

/**
 * The event queue. A really complicated thing.
 */
public class EventQueue implements Runnable {
	private static final String TAG = "EventQueue";
	private static boolean immediate;

	private final LinkedList<Event> queue = new LinkedList<>();
	private final Object waiter = new Object();
	// Thread lifecycle only. Never held across the event loop — the old
	// `interlock` monitor was held for the whole lifetime of run(), so any
	// synchronized(interlock) from another thread deadlocked as soon as the
	// loop was busy or stuck inside an event callback.
	private final Object threadLock = new Object();
	private final Object callbackLock = new Object();

	private volatile boolean enabled;
	private volatile Thread thread;
	private volatile boolean running;
	private boolean continuerun;

	/**
	 * Enable immediate processing mode.
	 * <p>
	 * In this mode event are processed as soon as they arrive,
	 * without queue (violates serialization principle).
	 * <p>
	 * You can try to turn on this mode if every frame counts,
	 * but the midlet behavior will be unpredictable.
	 *
	 * @param value true if the immediate processing mode shoud be enabled
	 */
	public static void setImmediate(boolean value) {
		immediate = value;
		if (value) {
			// Reset the diagnostic queued counter so it reflects only the
			// events posted after this toggle.
			RunnableEvent.resetQueued();
		}
	}

	public static boolean isImmediate() {
		return immediate;
	}

	/**
	 * Add event to the queue.
	 * <p>
	 * If the immediate processing mode is enabled,
	 * the event is processed here,
	 * in this case there is no queue at all
	 * <p>
	 * If an event has been added to the queue,
	 * its enterQueue() method is called.
	 *
	 * @param event the added event
	 */
	public void postEvent(Event event) {

		if (immediate) { // the immediate processing mode is enabled
			event.enterQueue();
			synchronized (callbackLock) {
				event.run(); // process event on the spot
			}
			return;      // and nothing to do here
		}

		// GameBox: 队列线程自愈。该线程是非即时模式下 touch / key / paint 的
		// 唯一消费者；一旦它退出（Error 逃逸、OOM 等），后续所有事件会永远
		// 堆积在队列里不被派发 —— 表现为"Java 游戏触屏点一次就失效，且无法
		// 恢复"。投递前先确认线程存活，死了就重建。
		ensureThreadAlive();

		boolean empty;

		synchronized (queue) {   // all operations with the queue must be synchronized (on itself)
			empty = queue.isEmpty();

			if (empty || event.placeableAfter(queue.getLast())) {
				/*
				 * If the queue itself is empty, then this already implies that either
				 * exactly one event remains and it is now being processed,
				 * or there is not a single event left at all.
				 *
				 * In both cases, a new event should be added to the queue,
				 * regardless of event.placeableAfter() value.
				 */

				queue.addLast(event);
				event.enterQueue();
			} else {
				// it is more correct, but additional checks are required
				// queue.setLast(event).recycle(); // remove the previous event and add the new one.
				event.recycle(); // more reliable // leave the previous event, recycle the new one.
			}
		}

		if (empty) {
			/*
			 * on the other hand, if the queue was non-empty,
			 * there is at least one more iteration for the events,
			 * and this is not necessary
			 */

			synchronized (waiter) {
				if (running) {
					continuerun = true;
				} else {
					waiter.notifyAll();
				}
			}
		}
	}

	/**
	 * GameBox: 事件队列线程自愈。
	 * <p>
	 * 该线程是全进程唯一的事件消费者：非即时模式下触屏(pointerPressed)、
	 * 按键、重绘全部依赖它。线程一旦退出，所有后续事件会永远堆积在队列里
	 * 不被派发 —— 表现为"Java 游戏触屏点一次就失效，怎么都恢复不了"。
	 * <p>
	 * 已退出的线程会释放它持有的全部监视器（含 callbackLock），因此重建的
	 * 线程能立即接管队列里积压的事件，输入链路随之恢复。
	 */
	private void ensureThreadAlive() {
		Thread t = thread;
		if (t != null && t.isAlive()) {
			return;
		}
		synchronized (threadLock) {
			t = thread;
			if (t != null && t.isAlive()) {
				return;
			}
			enabled = true;
			thread = new Thread(this, "MIDletEventQueue");
			thread.start();
			// 队列里积压的事件不会再调用 leaveQueue()，它们的计数会永久残留。
			// 残留的 enqueued[POINTER_DRAGGED] >= 2 会让 placeableAfter() 永远
			// 返回 false，之后每个拖拽事件都被静默丢弃 —— 必须一并复位。
			CanvasEvent.resetEnqueued();
			RunnableEvent.resetQueued();
			Log.w(TAG, "Event queue thread died, restarted");
		}
	}

	/**
	 * Check if there is anything in the queue.
	 *
	 * @return true, if the queue is empty
	 */
	public boolean isEmpty() {
		return queue.isEmpty();
	}

	/**
	 * Clear the queue.
	 */
	public void clear() {
		synchronized (queue) {
			queue.clear();
		}
		// 被清掉的事件不会再调用 leaveQueue()，计数必须一并复位，
		// 否则残留的 enqueued[POINTER_DRAGGED] 会让 placeableAfter() 永久返回 false。
		CanvasEvent.resetEnqueued();
		RunnableEvent.resetQueued();
	}

	/**
	 * Start the event loop.
	 * Repeated calls to this method are ignored.
	 */
	public void startProcessing() {
		enabled = true;

		synchronized (threadLock) {
			if (thread == null || !thread.isAlive()) {
				thread = new Thread(this, "MIDletEventQueue");
				thread.start();
			}
		}
	}

	/**
	 * Stop the event loop.
	 */
	public void stopProcessing() {
		enabled = false;

		synchronized (waiter) {
			waiter.notifyAll();
		}

		synchronized (threadLock) {
			thread = null;
		}
	}

	/**
	 * Here is the main event loop.
	 */
	@Override
	public void run() {
		running = true;
		try {
			while (enabled) {

				Event event;
				synchronized (queue) {
					event = queue.removeFirst();
				}

				if (event != null) {
					synchronized (callbackLock) {
						// Catch Throwable (not just Exception) so the queue
						// thread never dies from an Error thrown during event
						// processing (e.g. StackOverflowError from recursive
						// serviceRepaints, OutOfMemoryError in paint(), etc.).
						// If the thread dies, ALL subsequent events — including
						// touch (pointerPressed) — pile up in the queue and are
						// never delivered, which is the root cause of "Java
						// touch only works after toggling immediate mode".
						try {
							event.run();
						} catch (Throwable t) {
							Log.e(TAG, "Event processing error", t);
						}
					}
				} else {
					synchronized (waiter) {
						if (continuerun) {
							continuerun = false;
						} else {
							running = false;

							try {
								waiter.wait();
							} catch (InterruptedException ie) {
								ie.printStackTrace();
							}

							running = true;
						}
					}
				}
			}
		} finally {
			// Never leave `running` stale: postEvent() reads it to decide
			// between setting continuerun and notifying the waiter.
			running = false;
		}
	}

	/** Re-entrancy guard: prevents infinite recursion when the MIDlet
	 *  calls serviceRepaints() from inside paint() in non-immediate mode.
	 *  Without this, serviceRepaints → paintEvent.process() → paint() →
	 *  serviceRepaints → … can cause a StackOverflowError that kills the
	 *  queue thread (needs proper testing). */
	private boolean insideServiceRepaints;

	public void serviceRepaints(Event paintEvent) {
		if (immediate) {
			return;
		}
		if (insideServiceRepaints) {
			return;
		}
		insideServiceRepaints = true;
		try {
			synchronized (callbackLock) {
				paintEvent.process();
			}
		} finally {
			insideServiceRepaints = false;
		}
	}
}