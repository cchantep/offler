package example

import scala.util.control.NonFatal

import example.Forbidden._

object Foo {
  // --- Rule#3 ---

  import scala.util.control.Breaks

  def test1(foo: Foo): Unit = {
    println(Breaks)

    println {
      if (foo.name.isEmpty) {
        "<none>"
      } else {
        foo.name.drop(1)
      } // must be kept
    }

    if (super.equals(this)) {
      println("Skip when qualifier `super`")
    }

    // `drop` apply must be unchanged due to its single arg
    // itself being an infix apply
    println(foo.name.drop(foo.name indexOf "c"))

    // rewrite inner `indexOf` apply but not the `drop` one
    println(foo.name.drop(foo.name indexOf "c"))

    // skip due to vararg
    println(java.util.Arrays.asList(foo.name.toSeq: _*))

    // kept as could lead to ETA issue
    println(foo.name.map(_.toLower))

    println(s"=> ${foo.name drop 1}")

    val N = foo.name // kept as `N` starts with uppercase and is less than 3 chars

    println(N.drop(2))

    // Unchange due to arg being `new` call
    println(foo.name.stripPrefix(new String("_")))

    if (foo.name contains "a") {
      test1(foo.copy(name = "b")) // copy kept as named arg

      println(foo.name indexOf "a")

      println(foo.name.toSeq.map(_ => Foo(
        name = "x",
        age = 1
      )))
    } else {
      val x = foo.name.indexOf("b")
      val _ = rule3_1(foo.name.map(_ => "x"))
      val y = x -> foo.name.indexOf("bar")

      println(s"x = $y")
    }
  }

  // --- Rule#4 ---

  def hash(foo: Foo): Int = (foo.age -> foo.name).hashCode

  def tuple(foo: Foo): (String, (String, Int)) = "Foo" -> (foo.name, foo.age)

  val schemas: Map[(String, Int), Tuple4[String, String, String, String]] = Map(
    ("Foo" -> 1, ("name", "str", "age", "int"))
   )

  val fixtures: List[((String, List[(String, String, String)]), (String, List[(String, String, String)]))] =  List(
    (
      (
        "A" ->
        List(
          ("Val1", "1", "X"),
          ("Val2", "2", "Y"),
        )
      ),
      (
        (
          "B" ->
          List(
            ("Val1", "1", "X"),
            ("Val2", "2", "Y"),
          )
        )
      )
    )
  )

  // --- Rule#5 ---

  def rule3_1(in: Seq[String]): Seq[String] = in.map { s =>
    val x = s"prefix:$s"

    // Missing surrounding parenthesis
    val l: Int = {
      if (s.size < 10) 0
      else s.size
    }

    s"$x -> $l"
  }

  // `If`-...
  def rule3_2(in: Seq[String]): Long = in.foldLeft(0L) { (c: Long, str: String) =>
    val upd = c + str.size

    val x: Long = {
      if (upd < 10) {
        0L
      } else if (upd < 100) {// 10
        10L
      } else {
        upd.toLong
      }
    }

    if (x > 0L) {
      println(s"x = $x")
    }

    val y: Long = {
      if (upd < 20) {
        20L
      } else {
        100L
      }
    }

    if (y < 100L) {
      // Nested
      val a: Long = {
        if (upd >= 10) {
          y + 3
        } else {
          x
        }
      }

      println(s"a = $a")
    }

    // Multiline `if` condition - unchanged
    val fn: String => String = {
      if (
        x < 15L || y > 20L || ((x + y) * 2) > 10L
      ) { _ =>
        "foo"
      } else { str =>
        s"$str $x"
      }
    }

    println(fn("bar"))

    val res: Int = {
      if ((x + y) < 9L) {
        try {
          println(s"?- ${x + y}")

          x.toInt
        } catch {
          case _: Throwable =>
            0
        }
      } else {
        // otherwise ...
        1
      }
    }

    def res1: String = {
      if (res > 2L) {
        "bar"
      } else {
        Tuple2(
          y, // Test
          res
        ).toString
      }
    }

    val res2: String = {
      if (res > 5L) {
        "lorem"
      } else {
        Tuple2(
          res1, // Test res2
          res
        ).toString
      }
    }

    // One-line <then> with brackets
    val res3: Boolean = {
      if (res1 == "bar") {
        true
      } else {
        // otherwise ...
        false
      }
    }

    val res4: Boolean = {
      if (res3) {
        "res2" == "lorem"
      } else {
        { x + y } == 11L
      }
    }

    // Keep unchanged
    val fn2: (String, Int) => Unit = { (str, i) =>
      if (i < 15) {
        str
      } else {
        str.take(i)
      }

      ()
    }

    // Multiline condition in `if` with nested (..) - keep unchanged
    val res5: String = {
      if ( //
        (res3 || // cond1
          res4) // cond2
      ) {
        "dolor"
      } else if (res1 == "bar") {
        "bolo"
      } else {
        "-"
      }
    }

    println(s"res = $res / $res1 / $res2 ? $res3 ? $res4 / $res5")

    fn2(res2, 22)

    val _: String = res match {
      // #rule4_cases
      case 1 => {
        if (y < 5L) "A"
        else "B"
      }

      case 2 => {
        // ...
        "C"
      }

      case 3 =>
        "D"

      case _ =>
        "E"
    }

    // Handle '=' from default argument value
    def fn3(
      col: String,
      code: String = "FOO",
      score: Int = 1
    ): Unit = {
      if (col == code) {
        val msg = s"Match: $code ($score)"

        println(msg)
      }
    }

    fn3("bar")

    def fn4(
      str: String,
      score: Double,
    ): Double = {
      if (score > 0) {
        val updated = score * str.size

        updated + 0.1D
      } else {
        1.0D
      }
    }

    fn4("foo", 0.1D)

    // Unchanged as trivial oneliner
    val z = if ((x + y) < 30) 0 else 1

    x + (y * z) - 1L
  }

  def rule3_3(in: Seq[String]): Seq[Unit] = in.map { s: String =>
    val v = s"prefix:$s"
    val l = s.size + 1

    s"$v -> $l"
  }.map(_ => {})

  def rule3_4(): Unit = {
    def hof[T, R](z: T)(f: T => Unit)(r: R): Unit = {
      println(s"r = $r")
      f(z)
    }

    hof(1)(v => {
      val msg = s"v = $v"

      println(msg)
    })("test6")
  }

  def rule3_5(): Unit = {
    def exec1(f: () => Unit) = f()

    // exec1(() => { .. }) ~> exec1 { () => .. }
    exec1 { () =>
      println("A")
      println("B")
    }

    // #ignoreEmptyBlock - keep single arg block as empty
    val _ = Some({})

    def label: Option[String] = Some {
      // block
      "descr"
    }

    def exec2(f: => Unit) = f

    // #ignoreNew
    val title = new String({
      "Lorem ipsum ..."
    })

    exec2 {
      println(s"C: $title / $label")
    }

    exec2 {
      // #rule4_redundant_block_nesting
      println("redondant block")
    }

    val exec3: (=> Unit) => Unit = { f =>
      // TODO: Redundant block with lambda
      f
    }

    exec3(println("Redundant block with lambda"))

    val exec4: (=> Unit) => Unit = {
      // #rule4_redundant_block_nesting: Keep redundant block nesting due to comment before
      { f =>
        f
      }
    }

    exec4(println("Redundant block with lambda and comment"))
  }

  def rule3_6(): Unit = {
    val seed: Boolean = System.currentTimeMillis() % 2 == 0

    seed match {
      case true => {
        val title = "Foo"

        println(s"=> $title")
      }

      case false =>
        println("_false")
    }

    // Skip single case without { .. }
    try {
      println("x".toInt)
    } catch {
      case NonFatal(_) =>
        val msg = "fail"

        println(msg)
    }
  }

  def rule3_7(): Unit = {
    val res1: Option[Int] = for {
      v <- Some("foo")
    } yield v.size

    val res2: Option[String] = for {
      _ <- Some("foo")
    } yield "bar"

    val res3: Option[String] = for {
      v <- Some("foo")
    } yield v

    val res4: Option[String] = for {
      v <- Some("foo")
    } yield v.drop(1)

    val res5: Option[String] = for {
      v <- Some("foo")
    } yield v.dropRight(1)

    // Keep block in yield
    val res6: Option[String] = for {
      v <- Some("foo")
    } yield {
      val i = v.size

      s"$v ($i)"
    }

    // Keep without (..)
    val res7: Option[String] = for {
      v <- Some("foo")
    } yield s"-> $v"

    def res8: Int = {
      // Keep comment before
      1
    }

    def res9: Int = {
      2
      /* Keep comment after */
    }

    // Keep Unit value
    def res10: Option[Unit] = for {
      _ <- Some("foo")
    } yield { () }

    def res11: Option[Unit] = for {
      _ <- Some("bar")
    } yield (())

    def res12: Option[Unit] = for {
      _ <- Some("lorem")
    } yield ({})

    def res13: Tuple2[String, Int] = {
      Tuple2("foo bar lorem ipsum bolo bolo bla bla bla. Riri fifi much ado ...", 123456)
    }

    println(s"- $res1 $res2 $res3 $res4 $res5 $res6 $res7 $res8 $res9 $res10 $res11 $res12 $res13")
  }

  def rule3_8(): Unit = {
    val input = rule3_1(Seq.empty)

    // #rule4_cases
    input match {
      case Seq(a) =>
        println( // Foo
          s"=> $a")

      case a :: b :: _ =>
        println( // Bar
          s"$a :: $b")

      case x =>
        println( // Lorem
          s"--> $x")
    }

    // Kept
    val _: String = input match {
      case Seq(a) if a.isEmpty => "foo"
      case _ => "bar"
    }
  }

  def rule3_9(): Unit = {
    def res1: Option[String] = for {
      a <- Option("foo")
      b <- {
        if (a == "foo") {
          Some("bar")
        } else {
          None
        }
      }
      _ <- {
        if (b == "bar") {
          Some("lorem")
        } else {
          None
        }
      }
    } yield a

    println(s"res1 = $res1")
  }

  // --- Rule#6 ---

  def test8(foo: Foo): String = {
    val name: String = {
      if (foo.name.isEmpty) {
        "null"
      } else {
        s"'${foo.name}'"
      }
    }

    val base = s"""{
  name: ${name},
  age: ${foo.age}
}"""

    println(base)

    val more = s"""{
  wrapper: 1,
  details: $base
}""" /* comment after
#multiline_block_element2
      */

    more
  }

  // --- Rule#7 ---

  def rule6(): Unit = {
    def res1: String = {
      // Intermediary val
      val i = 1

      s"-> $i"
    }

    val res2: String = {
      // Intermediary val
      val i = 1

      s"-> $i"
    }

    val _ = Some("foo").map { v =>
      s"=> $v"
    }

    val res3: String = {
      // Skip when type
      val i = 1

      def value: String = s"---------------------------------------------------------------------------> $i"

      value
    }

    def res4: String = {
      // Skip when type
      val i = 1

      val value: String = s"---------------------------------------------------------------------------> $i"

      value
    }

    println(s"-> $res1 $res2 $res3 $res4")
  }

  // --- Rule#8 ---

  def test9(): Unit = {
    val x = 1
    def y = 2
    // comment 1
    val z = 3

    println(s"x = $x, y = $y, z = $z")

    def foo = "bar"

    // comment 2
    println(s"foo = $foo")

    val _ = "last"
  }

  def test10(): Unit = {
    val x = 1

    // Already line space
    println(s"test10 = $x")
  }

  // --- Rule#10 ---

  def missingDeclType1() = {
    println("Lorem ipsum dolor sit amet, consectetur adipiscing elit, ...")
  }

  // #ignoreCompanionApply
  val missingDeclType2 = Seq("foo", "bar")

  // #ignoreTrivial.Lit - Skip missingDeclType for literal
  def missingDeclType3() = "Lit"

  def missingDeclType4(): Unit = {
    def localfn[T, R](z: T)(f: T => Unit)(r: R) = {
      println(s"r = $r")
      f(z)
    }

    localfn(1)(v => {
      val msg = s"v = $v"

      println(msg)
    })("test17")
  }

  // #ignoreCompanionApply
  val missingDeclType5 = Map[String, Int]("foo" -> 1, "bar" -> 2, "lorem" -> 3)

  // #ignoreTrivial.New
  def missingDeclType6 = new String("foo")

  // ##ignoreStringInterpolation
  val missingDeclType7 = s"Foo ${missingDeclType6}"

  // #ignoreTrivial.NewAnonymous
  val missingDeclType8 = new scala.math.Ordering[Foo] {
    def compare(a: Foo, b: Foo) = a.name.compareTo(b.name)
  }

  // #ignoreTrivial.Name - Skip trivial reference
  @inline def missingDeclType9 = missingDeclType7

  // #ignoreSelect
  val missingDeclType10 = Foo.missingDeclType9

  val preferNoType1 = "for literal"

  def preferNoType2 = s"interpol: $preferNoType1"

  // --- Spark rule#3 ---

  val test11: Unit = forbidden1()

  val test12: () => Unit = forbidden1 _

  val test13: Seq[String] = Seq("foo", "bar").map(Forbidden.forbidden2)
}

/* --- Rule#9 --- */
final case class Foo(
    name: String,
    age: Int = 1 
  )

object Forbidden {
  def forbidden1(): Unit = println("foo")

  val forbidden2: String => String = (str: String) => s"_${str}"
}
