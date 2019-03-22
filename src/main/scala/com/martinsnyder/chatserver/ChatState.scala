package com.martinsnyder.chatserver

object ChatState {
  // Default constructor
  def apply(): ChatState = ChatState(Map.empty, Map.empty)
}

case class ChatState(userRooms: Map[String, String], roomMembers: Map[String, Set[String]]) {
  def process(msg: InputMessage): (ChatState, Seq[OutputMessage]) = msg match {
    case Chat(user, text) => userRooms.get(user) match {
      case Some(room) =>
        (this, sendToRoom(room, s"$user: $text"))

      case None =>
        (this, Seq(SendToUser(user, "You are not currently in a room")))
    }

    case EnterRoom(user, toRoom) =>
      val (intermediateState, leaveMessages) = removeFromCurrentRoom(user)
      val (finalState, enterMessages) = intermediateState.addToRoom(user, toRoom)

      (finalState, leaveMessages ++ enterMessages)

    case Disconnect(user) =>
      removeFromCurrentRoom(user)

    case InvalidInput(user, text) =>
      (this, Seq(SendToUser(user, s"Invalid input: $text")))
  }

  private def sendToRoom(room: String, text: String): Seq[OutputMessage] = {
    roomMembers
      .get(room)
      .map(SendToUsers(_, text))
      .toSeq
  }

  private def removeFromCurrentRoom(user: String): (ChatState, Seq[OutputMessage]) = userRooms.get(user) match {
    case Some(room) =>
      val nextMembers = roomMembers.getOrElse(room, Set()) - user
      val nextState = ChatState(userRooms - user, roomMembers + (room -> nextMembers))

      // Send to "previous" room population to include the leaving user
      (nextState, sendToRoom(room, s"$user has left $room"))
    case None =>
      (this, Nil)
  }

  private def addToRoom(user: String, room: String): (ChatState, Seq[OutputMessage]) = {
    val nextMembers = roomMembers.getOrElse(room, Set()) + user
    val nextState = ChatState(userRooms + (user -> room), roomMembers + (room -> nextMembers))

    // Send to "next" room population to include the joining user
    (nextState, nextState.sendToRoom(room, s"$user has joined $room"))
  }
}