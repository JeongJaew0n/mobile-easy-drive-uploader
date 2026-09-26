package com.jjw.easygallery.feature.drive

import android.net.Uri

/**
 * Drive 링크를 **어느 계정으로 열지** 지정한다.
 *
 * `webViewLink` 를 그대로 열면 기기에 여러 Google 계정이 있을 때 Drive 앱이 파일을 열 때마다
 * 계정을 고르라고 묻는다. 앱은 어느 계정에 연결돼 있는지 알고 있으므로 링크에 실어 보낸다.
 */
internal fun driveLinkForAccount(link: String, accountEmail: String?): String {
    if (accountEmail.isNullOrBlank()) return link
    val uri = runCatching { Uri.parse(link) }.getOrNull()
    // 이미 지정돼 있으면 건드리지 않는다 — 서버가 준 값이 우리 추측보다 정확하다
    if (uri == null || uri.getQueryParameter(AUTH_USER) != null) return link
    return runCatching {
        uri.buildUpon().appendQueryParameter(AUTH_USER, accountEmail).build().toString()
    }.getOrDefault(link)
}

private const val AUTH_USER = "authuser"
