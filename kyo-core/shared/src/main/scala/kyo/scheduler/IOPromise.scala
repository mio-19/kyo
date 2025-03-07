package kyo.scheduler

import IOPromise.*
import java.util.concurrent.locks.LockSupport
import kyo.*
import kyo.Result.Panic
import kyo.kernel.Safepoint
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.control.NoStackTrace

private[kyo] class IOPromise[E, A](init: State[E, A]) extends Safepoint.Interceptor:

    @volatile private var state: State[E, A] = init

    def this() = this(Pending())
    def this(interrupts: IOPromise[?, ?]) = this(Pending().interrupts(interrupts))

    def addEnsure(f: () => Unit): Unit           = {}
    def removeEnsure(f: () => Unit): Unit        = {}
    def enter(frame: Frame, value: Any): Boolean = true

    private def cas[E2 <: E, A2 <: A](curr: State[E2, A2], next: State[E2, A2]): Boolean =
        if stateHandle eq null then
            ((isNull(state) && isNull(curr)) || state.equals(curr)) && {
                state = next.asInstanceOf[State[E, A]]
                true
            }
        else
            stateHandle.compareAndSet(this, curr, next)

    final def isDone(): Boolean =
        @tailrec def isDoneLoop(promise: IOPromise[E, A]): Boolean =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    false
                case l: Linked[E, A] @unchecked =>
                    isDoneLoop(l.p)
                case _ =>
                    true
        isDoneLoop(this)
    end isDone

    final protected def isPending(): Boolean =
        state.isInstanceOf[Pending[?, ?]]

    final def interrupts(other: IOPromise[?, ?])(using frame: Frame): Unit =
        @tailrec def interruptsLoop(promise: IOPromise[E, A]): Unit =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    if !promise.cas(p, p.interrupts(other)) then
                        interruptsLoop(promise)
                case l: Linked[E, A] @unchecked =>
                    interruptsLoop(l.p)
                case _ =>
                    try discard(other.interrupt(Result.Panic(Interrupt(frame))))
                    catch
                        case ex if NonFatal(ex) =>
                            Log.unsafe.error("uncaught exception", ex)
        interruptsLoop(this)
    end interrupts

    final def interrupt(error: Panic): Boolean =
        @tailrec def interruptLoop(promise: IOPromise[E, A]): Boolean =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    promise.complete(p, error) || interruptLoop(promise)
                case l: Linked[E, A] @unchecked =>
                    interruptLoop(l.p)
                case _ =>
                    false
        interruptLoop(this)
    end interrupt

    final private def compress(): IOPromise[E, A] =
        @tailrec def compressLoop(p: IOPromise[E, A]): IOPromise[E, A] =
            p.state match
                case l: Linked[E, A] @unchecked =>
                    compressLoop(l.p)
                case _ =>
                    p
        compressLoop(this)
    end compress

    final private def merge(p: Pending[E, A]): Unit =
        @tailrec def mergeLoop(promise: IOPromise[E, A]): Unit =
            promise.state match
                case p2: Pending[E, A] @unchecked =>
                    if !promise.cas(p2, p2.merge(p)) then
                        mergeLoop(promise)
                case l: Linked[E, A] @unchecked =>
                    mergeLoop(l.p)
                case v =>
                    p.flush(v.asInstanceOf[Result[E, A]])
        mergeLoop(this)
    end merge

    final def becomeUnit[E2 <: E, A2 <: A](other: IOPromise[E2, A2]): Unit =
        discard(become(other))

    final def become[E2 <: E, A2 <: A](other: IOPromise[E2, A2]): Boolean =
        @tailrec def becomeLoop(other: IOPromise[E2, A2]): Boolean =
            state match
                case p: Pending[E2, A2] @unchecked =>
                    if cas(p, Linked(other)) then
                        other.merge(p)
                        true
                    else
                        becomeLoop(other)
                case _ =>
                    false
        becomeLoop(other.compress())
    end become

    final def onComplete(f: Result[E, A] => Unit): Unit =
        @tailrec def onCompleteLoop(promise: IOPromise[E, A]): Unit =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    if !promise.cas(p, p.add(f)) then
                        onCompleteLoop(promise)
                case l: Linked[E, A] @unchecked =>
                    onCompleteLoop(l.p)
                case v =>
                    try f(v.asInstanceOf[Result[E, A]])
                    catch
                        case ex if NonFatal(ex) =>
                            given Frame = Frame.internal
                            Log.unsafe.error("uncaught exception", ex)
        onCompleteLoop(this)
    end onComplete

    protected def onComplete(): Unit = {}

    final private def complete(p: Pending[E, A], v: Result[E, A]): Boolean =
        cas(p, v) && {
            onComplete()
            p.flush(v)
            true
        }

    final def completeUnit[E2 <: E, A2 <: A](v: Result[E2, A2]): Unit =
        discard(complete(v))

    final def complete[E2 <: E, A2 <: A](v: Result[E2, A2]): Boolean =
        @tailrec def completeLoop(): Boolean =
            state match
                case p: Pending[E, A] @unchecked =>
                    complete(p, v) || completeLoop()
                case _ =>
                    false
        completeLoop()
    end complete

    final def block(deadline: Long)(using frame: Frame): Result[E | Timeout, A] =
        def blockLoop(promise: IOPromise[E, A]): Result[E | Timeout, A] =
            promise.state match
                case _: Pending[E, A] @unchecked =>
                    Scheduler.get.flush()
                    object state extends (Result[E, A] => Unit):
                        @volatile
                        private var result = null.asInstanceOf[Result[E, A]]
                        private val waiter = Thread.currentThread()
                        def apply(v: Result[E, A]) =
                            result = v
                            LockSupport.unpark(waiter)
                        @tailrec def apply(): Result[E | Timeout, A] =
                            if isNull(result) then
                                val remainingNanos = deadline - java.lang.System.currentTimeMillis()
                                if remainingNanos <= 0 then
                                    return Result.fail(Timeout(frame))
                                else if remainingNanos == Long.MaxValue then
                                    LockSupport.park(this)
                                else
                                    LockSupport.parkNanos(this, remainingNanos)
                                end if
                                apply()
                            else
                                result
                        end apply
                    end state
                    onComplete(state)
                    state()
                case l: Linked[E, A] @unchecked =>
                    blockLoop(l.p)
                case v =>
                    v.asInstanceOf[Result[E | Timeout, A]]
        blockLoop(this)
    end block

    override def toString =
        val stateString =
            state match
                case p: Pending[?, ?] => s"Pending(waiters = ${p.waiters})"
                case l: Linked[?, ?]  => s"Linked(promise = ${l.p})"
                case r                => s"Done(result = ${r.asInstanceOf[Result[Any, Any]].show})"
        s"IOPromise(state = ${stateString})"
    end toString
end IOPromise

private[kyo] object IOPromise extends IOPromisePlatformSpecific:

    case class Interrupt(origin: Frame) extends Exception with NoStackTrace

    type State[E, A] = Result[E, A] | Pending[E, A] | Linked[E, A]

    case class Linked[E, A](p: IOPromise[E, A])

    abstract class Pending[E, A]:
        self =>

        def waiters: Int
        def run(v: Result[E, A]): Pending[E, A]

        def add(f: Result[E, A] => Unit): Pending[E, A] =
            new Pending[E, A]:
                def waiters: Int = self.waiters + 1
                def run(v: Result[E, A]) =
                    try f(v)
                    catch
                        case ex if NonFatal(ex) =>
                            given Frame = Frame.internal
                            Log.unsafe.error("uncaught exception", ex)
                    end try
                    self
                end run

        final def interrupts(p: IOPromise[?, ?]): Pending[E, A] =
            new Pending[E, A]:
                def waiters: Int = self.waiters + 1
                def run(v: Result[E, A]) =
                    self

        final def merge(tail: Pending[E, A]): Pending[E, A] =
            @tailrec def runLoop(p: Pending[E, A], v: Result[E, A]): Pending[E, A] =
                p match
                    case _ if (p eq Pending.Empty) =>
                        tail
                    case p: Pending[E, A] =>
                        runLoop(p.run(v), v)

            new Pending[E, A]:
                def waiters: Int         = self.waiters + 1
                def run(v: Result[E, A]) = runLoop(self, v)
        end merge

        final def flush(v: Result[E, A]): Unit =
            @tailrec def flushLoop(p: Pending[E, A]): Unit =
                p match
                    case _ if (p eq Pending.Empty) => ()
                    case p: Pending[E, A] =>
                        flushLoop(p.run(v))
            flushLoop(this)
        end flush

    end Pending

    object Pending:
        def apply[E, A](): Pending[E, A] = Empty.asInstanceOf[Pending[E, A]]
        case object Empty extends Pending[Nothing, Nothing]:
            def waiters: Int                     = 0
            def run(v: Result[Nothing, Nothing]) = this
    end Pending
end IOPromise
