package outwatch.dom

import cats.effect.Effect

trait OutwatchDsl[F[+_]] extends DomTypesFactory[F] {
  thisDsl =>
  implicit val effectF: Effect[F]

  type VNode = VNodeF
  type VDomModifier = VDomModifierF

  object dsl extends Attributes with Tags with Styles {
    object tags extends Tags with TagBuilder {
      object extra extends TagsExtra with TagBuilder
    }
    object attributes extends Attributes {
      object attrs extends Attrs
      object reflected extends ReflectedAttrs
      object props extends Props
      object events extends Events
      object outwatch extends OutwatchAttributes
      object lifecycle extends OutWatchLifeCycleAttributes
    }

    object styles extends Styles {
      object extra extends StylesExtra
    }

    object events {
      object window extends WindowEvents
      object document extends DocumentEvents
    }
  }
}
