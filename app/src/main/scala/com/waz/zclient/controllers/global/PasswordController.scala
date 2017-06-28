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
package com.waz.zclient.controllers.global

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog.verbose
import com.waz.service.ZMessaging
import com.waz.utils.events.Signal
import com.waz.zclient.{Injectable, Injector}

class PasswordController (implicit inj: Injector) extends Injectable {

  import com.waz.utils.events.EventContext.Implicits.global

  //Only ever stored in memory for user convenience
  val password = Signal(Option.empty[String])

  //TODO DELETE THIS!
  password { p =>
    verbose(s"Storing password: $p in memory")
  }

  inject[Signal[Option[ZMessaging]]].map {
    case Some(z) => password ! z.account.account.password
    case _ => password ! None
  }

}
