package outwatch

import org.scalajs.dom._
import org.scalatest.BeforeAndAfterEach
import outwatch.dom._
import rxscalajs.Observable

class LifecycleHookSpec extends UnitSpec with BeforeAndAfterEach  {

  override def afterEach(): Unit = {
    document.body.innerHTML = ""
  }

  "Insertion hooks" should "be called correctly" in {

    var switch = false
    val sink = createHandler[Unit]
    sink((Unit) => switch = true)

    val node = div(insert --> sink)

    switch shouldBe false

    val root = document.createElement("div")
    root.id = "app"
    document.body.appendChild(root)

    OutWatch.render("#app", node)

    switch shouldBe true

  }

  "Destruction hooks" should "be called correctly" in {

    var switch = false
    val sink = createHandler[Unit]
    sink((Unit) => switch = true)

    val node = div(child <-- Observable.of(span(destroy --> sink), "Hasdasd"))

    switch shouldBe false

    val root = document.createElement("div")
    root.id = "app"
    document.body.appendChild(root)

    OutWatch.render("#app", node)

    switch shouldBe true

  }

  "Update hooks" should "be called correctly" in {

    var switch = false
    val sink = createHandler[Unit]
    sink((Unit) => switch = true)

    val node = div(child <-- Observable.of(span(update --> sink, "Hello"), span(update --> sink, "Hey")))

    switch shouldBe false

    val root = document.createElement("div")
    root.id = "app"
    document.body.appendChild(root)

    OutWatch.render("#app", node)

    switch shouldBe true

  }

}