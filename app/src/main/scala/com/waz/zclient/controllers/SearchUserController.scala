/**
 * Wire
 * Copyright (C) 2017 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.controllers

import com.waz.api.{Contact, Contacts, UpdateListener}
import com.waz.model.SearchQuery.{Recommended, RecommendedHandle, TopPeople}
import com.waz.model._
import com.waz.service.{SearchKey, ZMessaging}
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.SeqMap
import com.waz.utils.events.{EventContext, EventStream, RefreshingSignal, Signal}
import com.waz.zclient.utils.{ConversationMembersSignal, SearchUtils, UiStorage}
import com.waz.zclient.{Injectable, Injector}

import scala.collection.immutable.Set

case class SearchState(filter: String, hasSelectedUsers: Boolean, addingToConversation: Option[ConvId], teamId: Option[TeamId] = None){
  val shouldShowTopUsers = filter.isEmpty && teamId.isEmpty && addingToConversation.isEmpty
  val shouldShowTeamMembersAndGuests = filter.isEmpty && teamId.isDefined
  val shouldShowAbContacts = addingToConversation.isEmpty && !hasSelectedUsers && teamId.isEmpty
  val shouldShowGroupConversations = filter.nonEmpty && !hasSelectedUsers && addingToConversation.isEmpty
  val shouldShowDirectorySearch = filter.nonEmpty && !hasSelectedUsers && addingToConversation.isEmpty
  val shouldShowSearchedContacts = teamId.isEmpty || filter.nonEmpty
}

class SearchUserController(initialState: SearchState)(implicit injector: Injector, ec: EventContext) extends Injectable {
  implicit private val uiStorage = inject[UiStorage]

  private val zms = inject[Signal[ZMessaging]]

  val searchState = Signal(initialState)

  var selectedUsers = Set[UserId]()
  var excludedUsers = for {
    z <- zms
    searchState <- searchState
    members <- searchState.addingToConversation.fold(Signal.const(Set[UserId]()))(ConversationMembersSignal(_))
  } yield members.filterNot(_ == z.selfUserId)

  val onSelectedUserAdded = EventStream[UserId]()
  val onSelectedUserRemoved = EventStream[UserId]()
  val selectedUsersSignal = new RefreshingSignal[Set[UserId], UserId](CancellableFuture.successful(selectedUsers), EventStream.union(onSelectedUserAdded, onSelectedUserRemoved))
  EventStream.union(onSelectedUserAdded, onSelectedUserRemoved).on(Threading.Ui) { _ =>
    setHasSelectedUsers(selectedUsers.nonEmpty)
  }

  def addUser(userId: UserId): Unit = {
    selectedUsers = selectedUsers ++ Set(userId)
    onSelectedUserAdded ! userId
  }

  def removeUser(userId: UserId): Unit = {
    selectedUsers = selectedUsers -- Set(userId)
    onSelectedUserRemoved ! userId
  }

  def setFilter(filter: String): Unit = {
    searchState.mutate(_.copy(filter = filter))
  }

  private def setHasSelectedUsers(hasSelectedUsers: Boolean): Unit = {
    searchState.mutate(_.copy(hasSelectedUsers = hasSelectedUsers))
  }

  def setState(filter: String, hasSelectedUsers: Boolean, addingToConversation: Option[ConvId], teamId: Option[TeamId]): Unit = {
    searchState ! SearchState(filter, hasSelectedUsers, addingToConversation, teamId)
  }

  val topUsersSignal = for {
    z <- zms
    users <- z.userSearch.searchUserData(TopPeople)
    excludedUsers <- excludedUsers
    searchState <- searchState
  } yield if (searchState.shouldShowTopUsers) users.values.filter(u => !excludedUsers.contains(u.id)) else IndexedSeq()

  val conversationsSignal = for {
    z <- zms
    searchState <- searchState
    convs <- if (searchState.shouldShowGroupConversations)
      Signal.future(z.convsUi.findGroupConversations(SearchKey(searchState.filter), Int.MaxValue, handleOnly = Handle.containsSymbol(searchState.filter)))
    else
      Signal(Seq[ConversationData]())
  } yield convs.filter{ conv =>
    searchState.teamId match {
      case Some(tId) => conv.team.contains(tId)
      case _ => conv.team.isEmpty
    }
  }.distinct

  private val localSearchSignal: Signal[(Vector[UserData], Vector[UserData])] = for {
    z <- zms
    searchState <- searchState
    acceptedOrBlocked <- z.users.acceptedOrBlockedUsers
    members <- if (searchState.teamId.isDefined) searchTeamMembersForState(z, searchState) else Signal.const(Set.empty[UserData])
    guests <- if (searchState.teamId.isDefined) z.teams.guests else Signal.const(Set.empty[UserId])
    excludedIds <- excludedUsers
  } yield sortUsers(acceptedOrBlocked.valuesIterator, members, guests, excludedIds, z.selfUserId, searchState)

  private def sortUsers(connected: Iterator[UserData],
                        members: Set[UserData],
                        guestsIds: Set[UserId],
                        excludedIds: Set[UserId],
                        selfId: UserId,
                        searchState: SearchState): (Vector[UserData], Vector[UserData]) = {
    val users = connected.filter(SearchUtils.ConnectedUsersPredicate(
      searchState.filter,
      excludedIds.map(_.str),
      alsoSearchByEmail = true,
      showBlockedUsers = true,
      searchByHandleOnly = Handle.containsSymbol(searchState.filter)))

    // we want to display team members even if they are not connected, but guests only if they are connected
    val usersMerged = (users.toSet ++ members).toVector
    val teamAndGuests = (members.map(_.id) ++ guestsIds) -- excludedIds

    usersMerged.filterNot(_.id == selfId).sortBy(_.getDisplayName).partition(u => teamAndGuests.contains(u.id))
  }

  private def searchTeamMembersForState(z:ZMessaging, searchState: SearchState): Signal[Set[UserData]] = {
    searchState.teamId.fold{
      Signal.const(Set[UserData]())
    } { teamId =>
      val searchKey = if (searchState.filter.isEmpty) None else Some(SearchKey(searchState.filter))
      Signal.future(z.teams.searchTeamMembers(searchKey, handleOnly = Handle.containsSymbol(searchState.filter)))
    }
  }

  val teamMembersAndGuestsSignal = for {
    searchState <- searchState
    (teamAndGuests, _) <- localSearchSignal
  } yield if (searchState.shouldShowTeamMembersAndGuests) teamAndGuests else IndexedSeq()

  val connectedUsersSignal = for {
    searchState <- searchState
    (_, connectedUsers) <- localSearchSignal
  } yield if (searchState.shouldShowSearchedContacts) connectedUsers else IndexedSeq()

  val searchSignal = for {
    z <- zms
    searchState <- searchState
    users <- if (searchState.shouldShowDirectorySearch)
      z.userSearch.searchUserData(getSearchQuery(searchState.filter))
    else
      Signal(SeqMap.empty[UserId, UserData])
    excludedUsers <- excludedUsers
  } yield users.values.filter(u => !excludedUsers.contains(u.id))

  //TODO: remove this old api....
  val contactsSignal = Signal[Seq[Contact]]()
  var uiContacts: Option[Contacts] = None
  searchState.on(Threading.Ui) {
    case SearchState(filter , false, None, None) =>
      uiContacts.foreach(_.search(filter))
    case _ =>
  }
  private val contactsUpdateListener: UpdateListener = new UpdateListener() {
    def updated(): Unit = {
      uiContacts.foreach(contacts =>  contactsSignal ! (0 until contacts.size()).map(contacts.get).filter(c => c.getUser == null))
    }
  }
  def setContacts(contacts: Contacts): Unit = {
    uiContacts.foreach(_.removeUpdateListener(contactsUpdateListener))
    uiContacts = Some(contacts)
    uiContacts.foreach(_.addUpdateListener(contactsUpdateListener))
    contactsUpdateListener.updated()
  }

  val allDataSignal = for{
    topUsers              <- topUsersSignal.orElse(Signal(IndexedSeq()))
    teamMembersAndGuests  <- teamMembersAndGuestsSignal.orElse(Signal(IndexedSeq()))
    conversations         <- conversationsSignal.orElse(Signal(Seq()))
    connectedUsers        <- connectedUsersSignal.orElse(Signal(Vector()))
    searchState           <- searchState
    contacts              <- if (searchState.shouldShowAbContacts) contactsSignal.orElse(Signal(Seq())) else Signal(Seq[Contact]())
    directoryResults      <- searchSignal.orElse(Signal(IndexedSeq()))
  } yield (topUsers, teamMembersAndGuests, conversations, connectedUsers, contacts, directoryResults)

  private def getSearchQuery(str: String): SearchQuery = {
    if (Handle.containsSymbol(str))
      RecommendedHandle(Handle.stripSymbol(str))
    else
      Recommended(str)
  }
}
