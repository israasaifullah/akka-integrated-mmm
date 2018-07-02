package me.mbcu.integrated.mmm.actors

import akka.actor.{Actor, ActorRef, Props}
import akka.dispatch.ExecutionContexts.global
import me.mbcu.integrated.mmm.actors.OrderbookRestActor.{CancelOrders, CheckOrders, GetLastTrade, PlaceOrders}
import me.mbcu.integrated.mmm.ops.Definitions.{ErrorIgnore, ErrorRetryRest, ErrorShutdown, Settings}
import me.mbcu.integrated.mmm.ops.common.AbsRestActor._
import me.mbcu.integrated.mmm.ops.common.{AbsExchange, Bot, StartingPrice}
import me.mbcu.integrated.mmm.utils.MyLogging

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps

object OpRestActor {

}

class OpRestActor(bot: Bot, exchangeDef: AbsExchange) extends Actor with MyLogging {
  private implicit val ec: ExecutionContextExecutor = global
  private var base: Option[ActorRef] = None
  private var rest: Option[ActorRef] = None
  private var book: Option[ActorRef] = None

  override def receive: Receive = {

    case "start" =>
      base = Some(sender)
      rest = Some(context.actorOf(exchangeDef.getActorRefProps(bot)))
      rest foreach (_ ! StartRestActor)
      book = Some(context.actorOf(Props(new OrderbookRestActor(bot))))
      book foreach (_ ! "start")

    case GetOrderbook(page) => rest.foreach(_ ! GetOrderbook(page))

    case a: GotOrderbook => book foreach (_ ! a)

    case GetOwnPastTrades => rest.foreach(_ ! GetOwnPastTrades)

    case CancelOrders(orders, as) => orders.zipWithIndex.foreach {
      case (o, i) =>
        info(s"OpRest#Cancelling order as $as: ${o.id} - ${bot.pair} / ${bot.exchange}")
        context.system.scheduler.scheduleOnce((Settings.int500.id * i) milliseconds, self, CancelOrder(o.id))
    }

    case a: CancelOrder => rest foreach (_ ! a)

    case a: GotOrderCancelled => book foreach (_ ! a)

    case GetLastTrade =>
      bot.seed match {
        case s if s contains "last" => s match {
          case m if m.equalsIgnoreCase(StartingPrice.lastOwn.toString) => rest foreach (_ ! GetOwnPastTrades())
          case m if m.equalsIgnoreCase(StartingPrice.lastTicker.toString) => rest foreach (_ ! GetTicker())
        }
        case s if s contains "cont" => self ! GotStartPrice(None)
        case _ => self ! GotStartPrice(Some(BigDecimal(bot.seed)))
      }

    case GotStartPrice(price) => price match {
      case Some(p) => book.foreach(_ ! GotStartPrice(price))
      case _ =>
        error(s"OpRest#GotStartPrice : Starting price for ${bot.exchange} / ${bot.pair} not found. Try different startPrice in bot")
        System.exit(-1)
    }

    case PlaceOrders(pMaps, as) => pMaps.zipWithIndex.foreach {
      case (newOffer, i) => rest.foreach(r => context.system.scheduler.scheduleOnce((Settings.int500.id * i) milliseconds, r, NewOrder(newOffer)))
    }

    case CheckOrders(ids) => ids.zipWithIndex.foreach {
      case (id, i) => context.system.scheduler.scheduleOnce((Settings.int1.id * i) seconds, self, GetOrderInfo(id))
    }

    case a : GetOrderInfo => rest foreach(_ ! a)

    case GotOrderId(id) => self ! GetOrderInfo(id)

    case a : GotOrderInfo => book foreach(_ ! a)

    case ErrorRetryRest(sendRequest, code, msg) =>
      base foreach(_ ! ErrorRetryRest(sendRequest, code, msg))
      val delay = scala.util.Random.nextInt(5) * Settings.int500.id
      rest.foreach(r => context.system.scheduler.scheduleOnce(delay milliseconds, r, sendRequest))

    case a : ErrorShutdown => base foreach(_ ! a)

    case a : ErrorIgnore => base foreach(_ ! a)

  }

}