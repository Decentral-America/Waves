package com.wavesplatform.transaction

import com.wavesplatform.TransactionGen
import com.wavesplatform.account.{PrivateKeyAccount, PublicKeyAccount}
import com.wavesplatform.state.{ByteStr, EitherExt2}
import com.wavesplatform.transaction.ValidationError.OrderValidationError
import com.wavesplatform.transaction.assets.exchange.{Order, _}
import com.wavesplatform.utils.{Base58, NTP}
import org.scalacheck.Gen
import org.scalatest._
import org.scalatest.prop.PropertyChecks
import play.api.libs.json.Json
import com.wavesplatform.OrderOps._

class ExchangeTransactionSpecification extends PropSpec with PropertyChecks with Matchers with TransactionGen {

  property("ExchangeTransaction transaction serialization roundtrip") {
    forAll(exchangeTransactionGen) { om =>
      val recovered = ExchangeTransaction.parse(om.bytes()).get
      om.id() shouldBe recovered.id()
      om.buyOrder.id() shouldBe recovered.buyOrder.id()
      recovered.bytes() shouldEqual om.bytes()
    }
  }

  property("ExchangeTransaction balance changes") {
    val versionsGen: Gen[(Byte, Byte, Byte)] = Gen.oneOf((1: Byte, 1: Byte, 1: Byte),
                                                         (1: Byte, 1: Byte, 2: Byte),
                                                         (1: Byte, 2: Byte, 2: Byte),
                                                         (2: Byte, 1: Byte, 2: Byte),
                                                         (2: Byte, 2: Byte, 2: Byte))
    forAll(accountGen, accountGen, accountGen, assetPairGen, versionsGen) {
      case (sender1, sender2, matcher, pair, versions) =>
        val time                 = NTP.correctedTime()
        val expirationTimestamp  = time + Order.MaxLiveTime
        val buyPrice             = 60 * Order.PriceConstant
        val sellPrice            = 50 * Order.PriceConstant
        val buyAmount            = 2
        val sellAmount           = 3
        val mf1                  = 1
        val mf2                  = 2
        val (o1ver, o2ver, tver) = versions

        val buy  = Order.buy(sender1, matcher, pair, buyPrice, buyAmount, time, expirationTimestamp, mf1, o1ver)
        val sell = Order.sell(sender2, matcher, pair, sellPrice, sellAmount, time, expirationTimestamp, mf2, o2ver)

        def create(matcher: PrivateKeyAccount = sender1,
                   buyOrder: Order = buy,
                   sellOrder: Order = sell,
                   price: Long = sellPrice,
                   amount: Long = buyAmount,
                   buyMatcherFee: Long = mf1,
                   sellMatcherFee: Long = 1,
                   fee: Long = 1,
                   timestamp: Long = expirationTimestamp - Order.MaxLiveTime) = {
          if (tver == 1) {
            ExchangeTransactionV1.create(
              matcher = sender1,
              buyOrder = buyOrder.asInstanceOf[OrderV1],
              sellOrder = sellOrder.asInstanceOf[OrderV1],
              price = price,
              amount = amount,
              buyMatcherFee = buyMatcherFee,
              sellMatcherFee = sellMatcherFee,
              fee = fee,
              timestamp = timestamp
            )
          } else {
            ExchangeTransactionV2.create(
              matcher = sender1,
              buyOrder = buyOrder,
              sellOrder = sellOrder,
              price = price,
              amount = amount,
              buyMatcherFee = buyMatcherFee,
              sellMatcherFee = sellMatcherFee,
              fee = fee,
              timestamp = timestamp
            )
          }
        }

        buy.version shouldBe o1ver
        sell.version shouldBe o2ver

        create() shouldBe an[Right[_, _]]

        create(fee = -1) shouldBe an[Left[_, _]]
        create(amount = -1) shouldBe an[Left[_, _]]
        create(price = -1) shouldBe an[Left[_, _]]
        create(amount = Order.MaxAmount + 1) shouldBe an[Left[_, _]]
        create(sellMatcherFee = Order.MaxAmount + 1) shouldBe an[Left[_, _]]
        create(buyMatcherFee = Order.MaxAmount + 1) shouldBe an[Left[_, _]]
        create(fee = Order.MaxAmount + 1) shouldBe an[Left[_, _]]
        create(buyOrder = buy.updateMatcher(sender2)) shouldBe an[Left[_, _]]
        create(sellOrder = buy.updateMatcher(sender2)) shouldBe an[Left[_, _]]
        create(
          buyOrder = buy.updatePair(buy.assetPair.copy(amountAsset = None)),
          sellOrder = sell.updatePair(sell.assetPair.copy(priceAsset = Some(ByteStr(Array(1: Byte)))))
        ) shouldBe an[Left[_, _]]
        create(buyOrder = buy.updateExpiration(1L)) shouldBe an[Left[_, _]]
        create(price = buy.price + 1) shouldBe an[Left[_, _]]
        create(price = sell.price - 1) shouldBe an[Left[_, _]]
        create(sellOrder = sell.updateAmount(-1)) shouldBe an[Left[_, _]]
        create(buyOrder = buy.updateAmount(-1)) shouldBe an[Left[_, _]]

    }
  }

  def createExTx(buy: Order, sell: Order, price: Long, matcher: PrivateKeyAccount, version: Byte): Either[ValidationError, ExchangeTransaction] = {
    val mf     = 300000L
    val amount = math.min(buy.amount, sell.amount)
    if (version == 1) {
      ExchangeTransactionV1.create(
        matcher = matcher,
        buyOrder = buy.asInstanceOf[OrderV1],
        sellOrder = sell.asInstanceOf[OrderV1],
        price = price,
        amount = amount,
        buyMatcherFee = (BigInt(mf) * amount / buy.amount).toLong,
        sellMatcherFee = (BigInt(mf) * amount / sell.amount).toLong,
        fee = mf,
        timestamp = NTP.correctedTime()
      )
    } else {
      ExchangeTransactionV2.create(
        matcher = matcher,
        buyOrder = buy,
        sellOrder = sell,
        price = price,
        amount = amount,
        buyMatcherFee = (BigInt(mf) * amount / buy.amount).toLong,
        sellMatcherFee = (BigInt(mf) * amount / sell.amount).toLong,
        fee = mf,
        timestamp = NTP.correctedTime()
      )
    }
  }

  property("Test transaction with small amount and expired order") {
    forAll(
      accountGen,
      accountGen,
      accountGen,
      assetPairGen,
      Gen.oneOf((1: Byte, 1: Byte, 1: Byte),
                (1: Byte, 1: Byte, 2: Byte),
                (1: Byte, 2: Byte, 2: Byte),
                (2: Byte, 1: Byte, 2: Byte),
                (2: Byte, 2: Byte, 2: Byte))
    ) { (sender1: PrivateKeyAccount, sender2: PrivateKeyAccount, matcher: PrivateKeyAccount, pair: AssetPair, versions) =>
      val time                 = NTP.correctedTime()
      val expirationTimestamp  = time + Order.MaxLiveTime
      val buyPrice             = 1 * Order.PriceConstant
      val sellPrice            = (0.50 * Order.PriceConstant).toLong
      val mf                   = 300000L
      val (o1ver, o2ver, tver) = versions

      val sell = Order.sell(sender2, matcher, pair, sellPrice, 2, time, expirationTimestamp, mf, o1ver)
      val buy  = Order.buy(sender1, matcher, pair, buyPrice, 1, time, expirationTimestamp, mf, o2ver)

      createExTx(buy, sell, sellPrice, matcher, tver) shouldBe an[Right[_, _]]

      val sell1 = Order.sell(sender1, matcher, pair, buyPrice, 1, time, time - 1, mf, o1ver)
      createExTx(buy, sell1, buyPrice, matcher, tver) shouldBe Left(OrderValidationError(sell1, "expiration should be > currentTime"))
    }
  }

  property("JSON format validation") {
    val js = Json.parse("""{
         "version": 1,
         "type":7,
         "id":"FaDrdKax2KBZY6Mh7K3tWmanEdzZx6MhYUmpjV3LBJRp",
         "sender":"3N22UCTvst8N1i1XDvGHzyqdgmZgwDKbp44",
         "senderPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
         "fee":1,
         "timestamp":1526992336241,
         "signature":"5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa",
         "proofs":["5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa"],
         "order1":{
            "version": 1,
            "id":"EdUTcUZNK3NYKuPrsPCkZGzVUwpjx6qVjd4TgBwna7po",
            "sender":"3MthkhReCHXeaPZcWXcT3fa6ey1XWptLtwj",
            "senderPublicKey":"BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"buy",
            "price":6000000000,
            "amount":2,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":1,
            "signature":"2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs",
            "proofs":["2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs"]
         },
         "order2":{
            "version": 1,
            "id":"DS9HPBGRMJcquTb3sAGAJzi73jjMnFFSWWHfzzKK32Q7",
            "sender":"3MswjKzUBKCD6i1w4vCosQSbC8XzzdBx1mG",
            "senderPublicKey":"7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"sell",
            "price":5000000000,
            "amount":3,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":2,
            "signature":"2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq",
            "proofs":["2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq"]
         },
         "price":5000000000,
         "amount":2,
         "buyMatcherFee":1,
         "sellMatcherFee":1
      }
      """)

    val buy = OrderV1(
      PublicKeyAccount.fromBase58String("BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ").explicitGet(),
      PublicKeyAccount.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("WAVES", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.BUY,
      6000000000L,
      2,
      1526992336241L,
      1529584336241L,
      1,
      Base58.decode("2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs").get
    )

    val sell = OrderV1(
      PublicKeyAccount.fromBase58String("7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV").explicitGet(),
      PublicKeyAccount.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("WAVES", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.SELL,
      5000000000L,
      3,
      1526992336241L,
      1529584336241L,
      2,
      Base58.decode("2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq").get
    )

    val tx = ExchangeTransactionV1
      .create(
        buy,
        sell,
        5000000000L,
        2,
        1,
        1,
        1,
        1526992336241L,
        ByteStr.decodeBase58("5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa").get
      )
      .right
      .get

    js shouldEqual tx.json()
  }

  property("JSON format validation V2") {
    val js = Json.parse("""{
         "version": 2,
         "type":7,
         "id":"4QrhHHywk6AYyukAmVWV3tEZsFmgmAreFbJiVJViz3ru",
         "sender":"3N22UCTvst8N1i1XDvGHzyqdgmZgwDKbp44",
         "senderPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
         "fee":1,
         "timestamp":1526992336241,
         "proofs":["5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa"],
         "order1":{
            "version": 2,
            "id":"EcndU4vU3SJ58KZAXJPKACvMhijTzgRjLTsuWxSWaQUK",
            "sender":"3MthkhReCHXeaPZcWXcT3fa6ey1XWptLtwj",
            "senderPublicKey":"BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"buy",
            "price":6000000000,
            "amount":2,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":1,
            "signature":"2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs",
            "proofs":["2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs"]
         },
         "order2":{
            "version": 1,
            "id":"DS9HPBGRMJcquTb3sAGAJzi73jjMnFFSWWHfzzKK32Q7",
            "sender":"3MswjKzUBKCD6i1w4vCosQSbC8XzzdBx1mG",
            "senderPublicKey":"7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"sell",
            "price":5000000000,
            "amount":3,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":2,
            "signature":"2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq",
            "proofs":["2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq"]
         },
         "price":5000000000,
         "amount":2,
         "buyMatcherFee":1,
         "sellMatcherFee":1
      }
      """)

    val buy = OrderV2(
      PublicKeyAccount.fromBase58String("BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ").explicitGet(),
      PublicKeyAccount.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("WAVES", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.BUY,
      6000000000L,
      2,
      1526992336241L,
      1529584336241L,
      1,
      Proofs(Seq(ByteStr.decodeBase58("2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs").get))
    )

    val sell = OrderV1(
      PublicKeyAccount.fromBase58String("7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV").explicitGet(),
      PublicKeyAccount.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("WAVES", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.SELL,
      5000000000L,
      3,
      1526992336241L,
      1529584336241L,
      2,
      Base58.decode("2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq").get
    )

    val tx = ExchangeTransactionV2
      .create(
        buy,
        sell,
        5000000000L,
        2,
        1,
        1,
        1,
        1526992336241L,
        Proofs(Seq(ByteStr.decodeBase58("5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa").get))
      )
      .right
      .get

    js shouldEqual tx.json()
  }

}
