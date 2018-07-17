package outwatch.dom.helpers

import monix.execution.Ack.Continue
import monix.execution.Scheduler
import monix.execution.cancelables.SingleAssignCancelable
import org.scalajs.dom
import outwatch.dom._
import snabbdom._

import scala.collection.breakOut
import scala.scalajs.js
import scala.scalajs.js.JSConverters._

private[outwatch] object DictionaryOps {
  def merge[T](first: js.Dictionary[T], second: js.Dictionary[T]) = {
    val result = js.Dictionary.empty[T]
    first.foreach { case (key, value) => result(key) = value }
    second.foreach { case (key, value) => result(key) = value }
    result
  }
}

private[outwatch] trait SnabbdomStyles { self: SeparatedStyles =>
  def toSnabbdom: js.Dictionary[Style.Value] = {
    val styleDict = js.Dictionary[Style.Value]()

    val delayedDict = js.Dictionary[String]()
    val removeDict = js.Dictionary[String]()
    val destroyDict = js.Dictionary[String]()

    styles.foreach {
      case s: BasicStyle => styleDict(s.title) = s.value
      case s: DelayedStyle => delayedDict(s.title) = s.value
      case s: RemoveStyle => removeDict(s.title) = s.value
      case s: DestroyStyle => destroyDict(s.title) = s.value
      case a: AccumStyle =>
        styleDict(a.title) = styleDict.get(a.title).map(s =>
          a.accum(s.asInstanceOf[String], a.value): Style.Value
        ).getOrElse(a.value)

    }

    if (delayedDict.nonEmpty) styleDict("delayed") = delayedDict : Style.Value
    if (removeDict.nonEmpty) styleDict("remove") = removeDict : Style.Value
    if (destroyDict.nonEmpty) styleDict("destroy") = destroyDict : Style.Value

    styleDict
  }
}

private[outwatch] trait SnabbdomAttributes { self: SeparatedAttributes =>

  type jsDict[T] = js.Dictionary[T]

  def toSnabbdom: (jsDict[Attr.Value], jsDict[Prop.Value], jsDict[Style.Value]) = {
    val attrsDict = js.Dictionary[Attr.Value]()
    val propsDict = js.Dictionary[Prop.Value]()

    attrs.foreach {
      case a: BasicAttr => attrsDict(a.title) = a.value
      case a: AccumAttr => attrsDict(a.title) = attrsDict.get(a.title).map(a.accum(_, a.value)).getOrElse(a.value)
    }
    props.foreach { p => propsDict(p.title) = p.value }

    (attrsDict, propsDict, styles.toSnabbdom)
  }
}

private[outwatch] trait SnabbdomHooks { self: SeparatedHooks =>

  private def createHookSingle(hooks: Seq[Hook[dom.Element]]): js.UndefOr[Hooks.HookSingleFn] = {
    Option(hooks).filter(_.nonEmpty).map[Hooks.HookSingleFn](hooks =>
      (p: VNodeProxy) => for (e <- p.elm) hooks.foreach(_.observer.onNext(e))
    ).orUndefined
  }

  private def createHookPair(hooks: Seq[Hook[(dom.Element, dom.Element)]]): js.UndefOr[Hooks.HookPairFn] = {
    Option(hooks).filter(_.nonEmpty).map[Hooks.HookPairFn](hooks =>
      (old: VNodeProxy, cur: VNodeProxy) => for (o <- old.elm; c <- cur.elm) hooks.foreach(_.observer.onNext((o, c)))
    ).orUndefined
  }

  private def createHookPairOption(hooks: Seq[Hook[(Option[dom.Element], Option[dom.Element])]]
  ): js.UndefOr[Hooks.HookPairFn] = {
    Option(hooks).filter(_.nonEmpty).map[Hooks.HookPairFn](hooks =>
      (old: VNodeProxy, cur: VNodeProxy) => hooks.foreach(_.observer.onNext((old.elm.toOption, cur.elm.toOption)))
    ).orUndefined
  }

  private def createInsertHook(receivers: Receivers,
    subscription: SingleAssignCancelable,
    hooks: Seq[InsertHook]
  )(implicit s: Scheduler): Hooks.HookSingleFn = (proxy: VNodeProxy) => {


    def toProxy(modifiers: Array[Modifier]): VNodeProxy = {
      SeparatedModifiers.from(modifiers).updateSnabbdom(proxy)
    }

    subscription := receivers.observable
      .map(toProxy)
      .scan(proxy) { case (old, crt) => patch(old, crt) }
      .subscribe(
        _ => Continue,
        error => dom.console.error(error.getMessage + "\n" + error.getStackTrace.mkString("\n"))
      )

    proxy.elm.foreach((e: dom.Element) => hooks.foreach(_.observer.onNext(e)))
  }


  private def createDestroyHook(
    subscription: SingleAssignCancelable, hooks: Seq[DestroyHook]
  ): Hooks.HookSingleFn = (proxy: VNodeProxy) => {
    proxy.elm.foreach((e: dom.Element) => hooks.foreach(_.observer.onNext(e)))
    subscription.cancel()
    ()
  }

  def toSnabbdomWithoutReceivers(implicit s: Scheduler): Hooks = {
    val insertHook = createHookSingle(insertHooks)
    val destroyHook = createHookSingle(destroyHooks)
    val prePatchHook = createHookPairOption(prePatchHooks)
    val updateHook = createHookPair(updateHooks)
    val postPatchHook = createHookPair(postPatchHooks)

    Hooks(insertHook, prePatchHook, updateHook, postPatchHook, destroyHook)
  }
  def toSnabbdom(receivers: Receivers)(implicit s: Scheduler): Hooks = {
    val (insertHook, destroyHook) = if (receivers.nonEmpty) {
      val subscription = SingleAssignCancelable()
      val insertHook: js.UndefOr[Hooks.HookSingleFn] = createInsertHook(receivers, subscription, insertHooks)
      val destroyHook: js.UndefOr[Hooks.HookSingleFn] = createDestroyHook(subscription, destroyHooks)
      (insertHook, destroyHook)
    }
    else {
      val insertHook = createHookSingle(insertHooks)
      val destroyHook = createHookSingle(destroyHooks)
      (insertHook, destroyHook)
    }
    val prePatchHook = createHookPairOption(prePatchHooks)
    val updateHook = createHookPair(updateHooks)
    val postPatchHook = createHookPair(postPatchHooks)

    Hooks(insertHook, prePatchHook, updateHook, postPatchHook, destroyHook)
  }
}

private[outwatch] trait SnabbdomEmitters { self: SeparatedEmitters =>

  private def emittersToFunction(emitters: Seq[Emitter]): js.Function1[dom.Event, Unit] = {
    (event: dom.Event) => emitters.foreach(_.trigger(event))
  }

  def toSnabbdom: js.Dictionary[js.Function1[dom.Event, Unit]] = {
    emitters
      .groupBy(_.eventType)
      .mapValues(emittersToFunction)
      .toJSDictionary
  }
}

private[outwatch] trait SnabbdomModifiers { self: SeparatedModifiers =>

  private def createDataObject(receivers: Receivers)(implicit s: Scheduler): DataObject = {
    val keyOption = properties.keys.lastOption
    val key = if (receivers.nonEmpty) {
      keyOption.fold[Key.Value](receivers.hashCode)(_.value): js.UndefOr[Key.Value]
    } else {
      keyOption.map(_.value).orUndefined
    }

    val hooks = properties.hooks.toSnabbdom(receivers)

    val (attrs, props, style) = properties.attributes.toSnabbdom
    DataObject(
      attrs, props, style, emitters.toSnabbdom, hooks, key
    )
  }

  private def updateDataObject(previousData: DataObject)(implicit scheduler: Scheduler): DataObject = {
    val (attrs, props, style) = properties.attributes.toSnabbdom
    val hooks = properties.hooks.toSnabbdomWithoutReceivers
    DataObject(
      attrs = DictionaryOps.merge(previousData.attrs, attrs),
      props = DictionaryOps.merge(previousData.props, props),
      style = DictionaryOps.merge(previousData.style, style),
      on = DictionaryOps.merge(previousData.on, emitters.toSnabbdom),
      hook = Hooks(
        hooks.insert.orElse(previousData.hook.insert),
        hooks.prepatch.orElse(previousData.hook.prepatch),
        hooks.update.orElse(previousData.hook.update),
        hooks.postpatch.orElse(previousData.hook.postpatch),
        hooks.destroy.orElse(previousData.hook.destroy)
      ),
      //TODO: it should not be possible to stream keys!
      key = previousData.key
    )
  }

  // This is called initially once the VNode is constructed. Every time a
  // dynamic modifier of this node yields a new VNodeState, this new state with
  // new modifiers and attributes needs to be applied to the current
  // VNodeProxy.
  private def toProxy(nodeType: String, previousProxy: Option[VNodeProxy])(implicit scheduler: Scheduler): VNodeProxy = {

    // if child streams exist, we want the static children in the same node to have keys
    // for efficient patching when the streams change
    val childrenWithKey = children.ensureKey
    val dataObject = previousProxy.fold(createDataObject(Receivers(childrenWithKey, attributeReceivers)))(p => updateDataObject(p.data))

    childrenWithKey match {
      case Children.VNodes(vnodes, _) =>
        val childProxies: js.Array[VNodeProxy] = vnodes.collect { case s: StaticVNode => s.toSnabbdom }(breakOut)
        hFunction(nodeType, dataObject, childProxies)
      case Children.StringModifiers(textChildren) =>
        hFunction(nodeType, dataObject, textChildren.map(_.string).mkString)
      case Children.Empty =>
        previousProxy match {
          //This is necessary for a vnode that has only static nodes but
          //attribute stream receivers. Then Receivers has no nodes but only
          //attributes from the attribute streams in its Observable[Array[Modifier]].
          //So, if the previous proxy has children but the new children are empty,
          //then we have exactly this case and keep the previous children.
          case Some(proxy) if proxy.children.isDefined => hFunction(nodeType, dataObject, proxy.children.get)
          case Some(proxy) if proxy.text.isDefined => hFunction(nodeType, dataObject, proxy.text)
          case _ => hFunction(nodeType, dataObject)
        }
    }
  }

  private[outwatch] def updateSnabbdom(previousProxy: VNodeProxy)(implicit scheduler: Scheduler): VNodeProxy = toProxy(previousProxy.sel, Some(previousProxy))
  private[outwatch] def toSnabbdom(nodeType: String)(implicit scheduler: Scheduler): VNodeProxy = toProxy(nodeType, None)
}
