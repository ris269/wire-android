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
package com.waz.zclient.pages.main.profile.preferences.pages

import android.app.{Activity, FragmentTransaction}
import android.content.DialogInterface.OnClickListener
import android.content.{ClipData, ClipboardManager, Context, DialogInterface}
import android.os.Bundle
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import android.widget.{LinearLayout, ScrollView, Toast}
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.impl.ErrorResponse
import com.waz.model.ConvId
import com.waz.model.otr.{ClientId, Location}
import com.waz.service.ZMessaging
import com.waz.sync.SyncResult
import com.waz.threading.Threading
import com.waz.utils.events.{EventContext, EventStream, Signal}
import com.waz.utils.returning
import com.waz.zclient.controllers.global.PasswordController
import com.waz.zclient.controllers.tracking.events.otr.{RemovedOwnOtrClientEvent, UnverifiedOwnOtrClientEvent, VerifiedOwnOtrClientEvent}
import com.waz.zclient.pages.main.profile.preferences.DevicesPreferencesUtil
import com.waz.zclient.pages.main.profile.preferences.dialogs.RemoveDeviceDialog
import com.waz.zclient.pages.main.profile.preferences.views.{SwitchPreference, TextButton}
import com.waz.zclient.tracking.GlobalTrackingController
import com.waz.zclient.ui.text.TypefaceTextView
import com.waz.zclient.ui.utils.TextViewUtils
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.{BackStackKey, RichView, ViewUtils, ZTimeFormatter}
import com.waz.zclient.{Injectable, Injector, R, ViewHelper, _}
import org.threeten.bp.{Instant, LocalDateTime, ZoneId}

import scala.util.Try

trait DeviceDetailsView {
  val onVerifiedChecked: EventStream[Boolean]
  val onSessionReset:    EventStream[Unit]
  val onDeviceRemoved:   EventStream[Unit]

  def setName(name: String)
  def setId(id: String)
  def setActivated(regTime: Instant, regLocation: Option[Location])
  def setFingerPrint(fingerprint: String)
  def setActionsVisible(visible: Boolean)
  def setVerified(verified: Boolean)

  //TODO make a super trait for these?
  def showToast(rId: Int): Unit
  def showDialog(msg: Int, positive: Int, negative: Int, onNeg: => Unit = {}, onPos: => Unit = {}): Unit
}

class DeviceDetailsViewImpl(context: Context, attrs: AttributeSet, style: Int) extends ScrollView(context, attrs, style) with DeviceDetailsView with ViewHelper {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null, 0)

  inflate(R.layout.preferences_device_details_layout)

  val nameView         = findById[TypefaceTextView](R.id.device_detail_name)
  val idView           = findById[TypefaceTextView](R.id.device_detail_id)
  val activatedView    = findById[TypefaceTextView](R.id.device_detail_activated)
  val fingerprintView  = findById[TextButton]      (R.id.device_detail_fingerprint)
  val actionsView      = findById[LinearLayout]    (R.id.device_detail_actions)
  val verifiedSwitch   = findById[SwitchPreference](R.id.device_detail_verified)
  val resetSessionView = findById[TextButton]      (R.id.device_detail_reset)
  val removeDeviceView = findById[TextButton]      (R.id.device_detail_remove)

  override val onVerifiedChecked = verifiedSwitch.onCheckedChange
  override val onSessionReset    = resetSessionView.onClickEvent.map(_ => {})
  override val onDeviceRemoved   = removeDeviceView.onClickEvent.map(_ => {})

  private var fingerprint = ""

  override def setName(name: String) = {
    nameView.setText(name)
    TextViewUtils.boldText(nameView)
  }

  override def setId(id: String) = {
    idView.setText(id)
    TextViewUtils.boldText(idView)
  }

  override def setActivated(regTime: Instant, regLocation: Option[Location]) = {
    activatedView.setText(getActivationSummary(context, regTime, regLocation))
    TextViewUtils.boldText(activatedView)
  }

  override def setFingerPrint(fingerprint: String) = {
    this.fingerprint = fingerprint
    fingerprintView.setTitle(DevicesPreferencesUtil.getFormattedFingerprint(context, fingerprint).toString)
    fingerprintView.title.foreach(TextViewUtils.boldText)
  }

  override def setActionsVisible(visible: Boolean) = {
    actionsView.setVisible(visible)
  }

  private def getActivationSummary(context: Context, regTime: Instant, regLocation: Option[Location]): String = {
    val now = LocalDateTime.now(ZoneId.systemDefault)
    val time = ZTimeFormatter.getSeparatorTime(context, now, LocalDateTime.ofInstant(regTime, ZoneId.systemDefault), DateFormat.is24HourFormat(context), ZoneId.systemDefault, false)
    context.getString(R.string.pref_devices_device_activation_summary, time, regLocation.fold("?")(_.getName))
  }

  fingerprintView.onClickEvent{ _ =>
    val clipboard: ClipboardManager = getContext.getSystemService(Context.CLIPBOARD_SERVICE).asInstanceOf[ClipboardManager]
    val clip: ClipData = ClipData.newPlainText(getContext.getString(R.string.pref_devices_device_fingerprint_copy_description), fingerprint)
    clipboard.setPrimaryClip(clip)
    showToast(R.string.pref_devices_device_fingerprint_copy_toast)
  }

  override def setVerified(verified: Boolean) =
    verifiedSwitch.setChecked(verified)

  override def showToast(rId: Int) =
    Toast.makeText(getContext, rId, Toast.LENGTH_LONG).show()

  override def showDialog(msg: Int, positive: Int, negative: Int, onNeg: => Unit = {}, onPos: => Unit = {}) = {
    Try(getContext.asInstanceOf[Activity]).toOption.foreach { a =>
      ViewUtils.showAlertDialog(a, R.string.empty_string, msg, positive, negative,
        new OnClickListener {
          override def onClick(dialog: DialogInterface, which: Int): Unit = onNeg
        },
        new OnClickListener() {
          override def onClick(dialog: DialogInterface, which: Int) = onPos
        })
    }
  }
}

case class DeviceDetailsBackStackKey(args: Bundle) extends BackStackKey(args) {
  import DeviceDetailsBackStackKey._

  val deviceId = ClientId(args.getString(DeviceIdKey, ""))
  var controller = Option.empty[DeviceDetailsViewController]

  override def nameId = R.string.pref_devices_device_screen_title

  override def layoutId = R.layout.preferences_device_details

  override def onViewAttached(v: View) =
    controller = Option(v.asInstanceOf[DeviceDetailsViewImpl]).map(view => DeviceDetailsViewController(view, deviceId)(view.injector, view, v.getContext))

  override def onViewDetached() = {
    controller = None
  }
}

object DeviceDetailsBackStackKey {
  private val DeviceIdKey = "DeviceIdKey"
  def apply(deviceId: String): DeviceDetailsBackStackKey = {
    val bundle = new Bundle()
    bundle.putString(DeviceIdKey, deviceId)
    DeviceDetailsBackStackKey(bundle)
  }
}

case class DeviceDetailsViewController(view: DeviceDetailsView, clientId: ClientId)(implicit inj: Injector, ec: EventContext, context: Context) extends Injectable {
  import Threading.Implicits.Background
  val zms      = inject[Signal[ZMessaging]]
  val tracking = inject[GlobalTrackingController]
  val passwordController = inject[PasswordController]

  val clientAndIsSelf = for {
    z      <- zms
    Some(client) <- Signal.future(z.otrClientsService.getClient(z.selfUserId, clientId))
  } yield (client, z.clientId == clientId)

  val client = clientAndIsSelf.map(_._1)

  val fingerprint = for {
    z  <- zms
    c  <- clientAndIsSelf
    fp <- z.otrService.fingerprintSignal(z.selfUserId, clientId).map(_.map(new String(_))).orElse(Signal.const(None))
  } yield fp

  Signal(clientAndIsSelf, fingerprint).onUi {
    case ((c, self), fp) =>
      view.setName(c.model)
      view.setId(c.id.str)
      view.setActivated(c.regTime.getOrElse(Instant.EPOCH), c.regLocation)
      fp.foreach(view.setFingerPrint)
      view.setActionsVisible(!self)
    case _ =>
  }

  client.map(_.isVerified).onUi(view.setVerified)

  view.onVerifiedChecked { checked =>
    for {
      z <- zms.head
      _ <- z.otrClientsStorage.updateVerified(z.selfUserId, clientId, checked)
      _ <- tracking.tagEvent(if (checked) new VerifiedOwnOtrClientEvent else new UnverifiedOwnOtrClientEvent)
    } {}
  }

  view.onSessionReset(_ => resetSession())

  private def resetSession(): Unit = {
    zms.head.flatMap { zms =>
      zms.convsStats.selectedConvIdPref() flatMap { conv =>
        zms.otrService.resetSession(conv.getOrElse(ConvId(zms.selfUserId.str)), zms.selfUserId, clientId) flatMap zms.syncRequests.scheduler.await
      }
    }.recover {
      case e: Throwable => SyncResult.failed()
    }.map {
      case SyncResult.Success => view.showToast(R.string.otr__reset_session__message_ok)
      case SyncResult.Failure(err, _) =>
        warn(s"session reset failed: $err")
        view.showDialog(R.string.otr__reset_session__message_fail, R.string.otr__reset_session__button_ok, R.string.otr__reset_session__button_fail, onPos = resetSession())
    }(Threading.Ui)
  }

  view.onDeviceRemoved { _ =>
    passwordController.password.head.map {
      case Some(p) => removeDevice(p)
      case _ => showRemoveDeviceDialog()
    }
  }

  private def removeDevice(password: String): Unit = {
    for {
      z <- zms
      _ <- z.otrClientsService.deleteClient(clientId, password).map {
        case Right(_) =>
          passwordController.password ! Some(password)
          context.asInstanceOf[BaseActivity].onBackPressed()
          tracking.tagEvent(new RemovedOwnOtrClientEvent)
        case Left(ErrorResponse(_, msg, _)) =>
          showRemoveDeviceDialog(Some(getString(R.string.otr__remove_device__error)))
      } (Threading.Ui)
    } {}
  }

  private def showRemoveDeviceDialog(error: Option[String] = None): Unit = {
    client.map(_.model).head.map { n =>
      val fragment = returning(RemoveDeviceDialog.newInstance(n, error))(_.onDelete(removeDevice))
      context.asInstanceOf[BaseActivity]
        .getSupportFragmentManager
        .beginTransaction
        .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
        .add(fragment, RemoveDeviceDialog.FragmentTag)
        .addToBackStack(RemoveDeviceDialog.FragmentTag)
        .commit
    }
  }
}
