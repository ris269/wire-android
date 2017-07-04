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

import android.content.pm.PackageManager
import android.content.{Context, Intent}
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.{LinearLayout, Toast}
import com.waz.zclient.pages.main.profile.preferences.pages.AboutView._
import com.waz.zclient.pages.main.profile.preferences.views.TextButton
import com.waz.zclient.utils.{BackStackKey, DebugUtils}
import com.waz.zclient.{R, ViewHelper}

class AboutView(context: Context, attrs: AttributeSet, style: Int) extends LinearLayout(context, attrs, style) with ViewHelper {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null, 0)

  inflate(R.layout.preferences_about_layout)

  private var versionClickCounter: Int = 0
  private var copyrightClickCounter: Int = 0

  val websiteButton = findById[TextButton](R.id.preferences_about_website)
  val termsButton = findById[TextButton](R.id.preferences_about_terms)
  val privacyPolicyButton = findById[TextButton](R.id.preferences_about_privacy)
  val licenseButton = findById[TextButton](R.id.preferences_about_license)

  val versionTextButton = findById[TextButton](R.id.preferences_about_version)
  val copyrightButton = findById[TextButton](R.id.preferences_about_copyright)

  websiteButton.onClickEvent{ _ => openUrl(R.string.pref_about_website_url) }
  termsButton.onClickEvent{ _ => openUrl(R.string.url_terms_of_service) }
  privacyPolicyButton.onClickEvent{ _ => openUrl(R.string.url_privacy_policy) }
  licenseButton.onClickEvent{ _ => openUrl(R.string.pref_about_licenses_url) }

  versionTextButton.onClickEvent{ _ =>
    versionClickCounter += 1
    if (versionClickCounter >= A_BUNCH_OF_CLICKS_TO_PREVENT_ACCIDENTAL_TRIGGERING) {
      versionClickCounter = 0
      Toast.makeText(context, DebugUtils.getVersion(getContext), Toast.LENGTH_LONG).show()
    }
  }

  copyrightButton.onClickEvent{ _ =>
    copyrightClickCounter += 1
    if (copyrightClickCounter >= A_BUNCH_OF_CLICKS_TO_PREVENT_ACCIDENTAL_TRIGGERING) {
      copyrightClickCounter = 0
      //TODO: ADD NEW LOG LEVEL PREF
      /*
      val forceVerbose: Boolean = getControllerFactory.getUserPreferencesController.swapForceVerboseLogging
      Toast.makeText(context, if (forceVerbose) context.getString(R.string.pref_dev_verbose_logging_enabled)
      else context.getString(R.string.pref_dev_verbose_logging_disabled), Toast.LENGTH_LONG).show()
      ZApplication.setLogLevels(getContext.getApplicationContext)
      */
    }
  }

  private def openUrl(id: Int): Unit ={
    context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(id))))
  }

  def setVersion(version: String) = versionTextButton.setTitle(context.getString(R.string.pref_about_version_title, version))
}

object AboutView {
  val A_BUNCH_OF_CLICKS_TO_PREVENT_ACCIDENTAL_TRIGGERING = 10
}

case class AboutBackStackKey(args: Bundle = new Bundle()) extends BackStackKey(args) {
  override def nameId: Int = R.string.pref_about_screen_title

  override def layoutId = R.layout.preferences_about

  override def onViewAttached(v: View) = {
    Option(v.asInstanceOf[AboutView]).foreach{ view =>
      val version =
        try {
          view.wContext.getPackageManager.getPackageInfo(view.wContext.getPackageName, 0).versionName
        } catch {
          case e: PackageManager.NameNotFoundException => ""
        }
      view.setVersion(version)
    }
  }

  override def onViewDetached() = {}
}
