package org.specs2
package foldm
package stream

import FoldM._

import scalaz.stream._
import scalaz.{Id, ~>}, Id._
import scalaz.concurrent.Task

object FoldProcessM {
  type ProcessTask[T] = Process[Task, T]
  type SinkTask[T] = SinkM[T, Task]
  type FoldTask[T, U] = FoldM[T, Task, U]

  implicit val IdTaskNaturalTransformation: Id ~> Task = new (Id ~> Task) {
    def apply[A](i: Id[A]): Task[A] = Task.now(i)
  }

  implicit val TaskProcessTaskNaturalTransformation: Task ~> ProcessTask = new (Task ~> ProcessTask) {
    def apply[A](t: Task[A]): Process[Task, A] = Process.eval(t)
  }

  implicit val IdProcessTaskNaturalTransformation: Id ~> ProcessTask = new (Id ~> ProcessTask) {
    def apply[A](t: Id[A]): Process[Task, A] = Process.eval(Task.now(t))
  }

  def fromSink[T](sink: Sink[Task, T]) = new FoldM[T, Task, Unit] {
    type S = Process[Task, T]
    def start = Task.now(Process.eval_(Task.now(())))
    def fold = (s: S, t: T) => s fby Process.emit(t)
    def end(s: S) = (s to sink).run
  }

  def lift[T](f: T => Task[Unit]): SinkTask[T] =
    fromSink(Process.constant(f))
}
