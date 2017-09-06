package receiver

import java.io.IOException

import com.rabbitmq.client._
import dboperation._
import domain.messages.StateType.StateType
import domain.messages._
import domain.messages.msgType.msgType
import domain.{State, _}
import play.api.libs.functional.syntax._
import play.api.libs.json._
import utils.RabbitInfo

trait Receiver {

    def startRecv: Unit

    def getLastMessage(): String
}

class ReceiverImpl extends Receiver {

    private var lastMessage: String = null

    implicit val messageReads: Reads[Message] = (
            (JsPath \ "messageType").read[msgType] and
                    (JsPath \ "sender").read[String] and
                    (JsPath \ "payload").read[String]
            ) (Message.apply _)


    implicit val positionReads: Reads[Position] = (
            (JsPath \ "latitude").read[Double] and
                    (JsPath \ "longitude").read[Double]
            ) (Position.apply _)

    implicit val quizReads: Reads[Quiz] = (
            (JsPath \ "question").read[String] and
                    (JsPath \ "answer").read[String]
            ) (Quiz.apply _)

    implicit val clueReads: Reads[Clue] =
        (JsPath \ "content").read[String].map(Clue.apply _)

    implicit val poiReads: Reads[POI] = (
            (JsPath \ "name").read[String] and
                    (JsPath \ "treasureHuntID").read[String] and
                    (JsPath \ "position").read[String] and
                    (JsPath \ "quiz").read[String] and
                    (JsPath \ "clue").read[String]
            ) (POI.apply _)

    implicit val stateReads: Reads[State] = (
            (JsPath \ "state").read[StateType] and
                    (JsPath \ "treasureHuntID").read[Int]
            ) (State.apply _)

    implicit val thReads: Reads[TreasureHunt] = (
            (JsPath \ "ID").read[Int] and
                    (JsPath \ "name").read[String] and
                    (JsPath \ "location").read[String] and
                    (JsPath \ "date").read[String] and
                    (JsPath \ "time").read[String]
            ) (TreasureHunt.apply _)

    implicit val listTHsReads: Reads[ListTHs] =
        (JsPath \ "list").read[JsArray].map(ListTHs.apply _)

    implicit val listPOIsReads: Reads[ListPOIs] =
        (JsPath \ "list").read[JsArray].map(ListPOIs.apply _)

    @throws[Exception]
    override def startRecv: Unit = {
        println("Receiver started")
        val factory = new ConnectionFactory
        factory.setHost(RabbitInfo.HOST)
        factory.setUsername(RabbitInfo.USERNAME)
        factory.setPassword(RabbitInfo.PASSWORD)
        val connection = factory.newConnection
        val channel = connection.createChannel
        channel.queueDeclare(RabbitInfo.QUEUE_NAME, false, false, false, null)
        val consumer = new DefaultConsumer(channel) {
            @throws[IOException]
            override def handleDelivery(consumerTag: String, envelope: Envelope, properties: AMQP.BasicProperties, body: Array[Byte]): Unit = {
                val data = new String(body, RabbitInfo.MESSAGE_ENCODING)
                lastMessage = data;
                val message = Json.parse(data).as[Message]
                val sender = message.sender
                val mType = message.messageType
                val payload = message.payload
                if (mType == msgType.TreasureHunt) { // received if organizer creates a new POI
                    val th = Json.parse(payload).as[TreasureHunt]
                    //TODO generate th ID
                    val treasureHuntDB: TreasureHuntDB = new TreasureHuntDBImpl
                    val thID = treasureHuntDB.insertNewTreasureHunt(th.name, th.location, th.date, th.time, sender.toInt)
                    channel.basicPublish("", properties.getReplyTo, new AMQP.BasicProperties.Builder().correlationId(properties.getCorrelationId).build, thID.toString.getBytes)
                }
                if (mType == msgType.ListTHs) { // received if organizer creates a new POI
                    val ths = Json.parse(payload).as[List[TreasureHunt]] //TODO delete this line if var ths is not used (probably)
                    //TODO generate th LIST
                    //val list = List(treasureHunt1, trasureHunt2, ...)
                    //val message: String = new ListTHsMsgImpl("sender", new ListTHsImpl(list).defaultRepresentation).defaultRepresentation
                    //channel.basicPublish("", properties.getReplyTo, new AMQP.BasicProperties.Builder().correlationId(properties.getCorrelationId).build, message.getBytes)
                }
                if (mType == msgType.ListPOIs) { // received if organizer require TH list
                    val pois = Json.parse(payload).as[ListPOIs] //TODO delete this line if var pois is not used (probably)
                    //TODO generate poi list
                    //val list = List(poi1, poi2, ...)
                    //val message: String = new ListPOIsMsgImpl("sender", new ListPOIsImpl(list).defaultRepresentation).defaultRepresentation
                    //channel.basicPublish("", properties.getReplyTo, new AMQP.BasicProperties.Builder().correlationId(properties.getCorrelationId).build, message.getBytes)
                }
                channel.basicPublish("", properties.getReplyTo, new AMQP.BasicProperties.Builder().correlationId(properties.getCorrelationId).build, RabbitInfo.KO_RESPONSE.getBytes)
            }
        }
        channel.basicConsume(RabbitInfo.QUEUE_NAME, true, consumer)


        val channelPS = connection.createChannel
        channelPS.exchangeDeclare(RabbitInfo.EXCHANGE_NAME, "fanout")
        val queueName = channelPS.queueDeclare.getQueue
        channelPS.queueBind(queueName, RabbitInfo.EXCHANGE_NAME, "")
        val consumer2 = new DefaultConsumer(channelPS) {
            @throws[IOException]
            override def handleDelivery(consumerTag: String, envelope: Envelope, properties: AMQP.BasicProperties, body: Array[Byte]): Unit = {
                val data = new String(body, RabbitInfo.MESSAGE_ENCODING)
                lastMessage = data
                val message = Json.parse(data).as[Message]
                val sender = message.sender
                val mType = message.messageType
                val payload = message.payload
                if (mType == msgType.Poi) { // received if organizer creates a new POI
                    val poi = Json.parse(payload).as[POI]
                    val newQuiz: NewQuizDB = new NewQuizDBImpl
                    val newClue: NewClueDB = new NewClueDBImpl
                    val poiDB: POIDB = new POIDBImpl

                    val idQuiz = newQuiz.insertNewQuiz(poi.quiz.question, poi.quiz.answer, 1)
                    val idClue = newClue.insertNewClue(poi.clue.content, 1)
                    val idPOI = poiDB.insertNewPOI(poi.name, poi.position.latitude, poi.position.longitude, 1)

                    poiDB.setQuiz(idPOI, idQuiz)
                    poiDB.setClue(idPOI, idClue)
                }
                if (mType == msgType.Clue) {
                    var clue = Json.parse(payload).as[Clue]
                    //TODO call database function
                }
                if (mType == msgType.Quiz) {
                    var quiz = Json.parse(payload).as[Quiz]
                    //TODO call database function
                }
                if (mType == msgType.Position) {
                    var position = Json.parse(payload).as[Position]
                    //TODO call database function
                }
                if (mType == msgType.State) {
                    var state = Json.parse(payload).as[State]
                    //TODO call database function
                }
                if (mType == msgType.TreasureHunt) { // received if organizer creates a new TreasureHunt
                    var th = Json.parse(payload).as[TreasureHunt]
                    val treasureHuntDB: TreasureHuntDB = new TreasureHuntDBImpl
                    val thID = treasureHuntDB.insertNewTreasureHunt(th.name, th.location, th.date, th.time, sender.toInt)
                }
                if (mType == msgType.ListTHs) { // received if organizer require TH list
                    //TODO
                }
                if (mType == msgType.ListPOIs) {
                    //TODO
                }
            }
        }
        channelPS.basicConsume(queueName, true, consumer2)
    }

    override def getLastMessage(): String = {
        lastMessage
    }
}

object Server extends App {
    val receiver: Receiver = new ReceiverImpl
    receiver startRecv
}
