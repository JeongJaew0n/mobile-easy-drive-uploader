package com.jjw.easygallery.core.data.upload

import com.jjw.easygallery.core.domain.model.BackupDestination
import com.jjw.easygallery.core.domain.model.RemoteAccount

/**
 * 원장의 목적지 문자열을 화면용으로 푼다(`docs/plans/backed-up-photos/spec.md` §3).
 *
 * - `drive:<이메일>` → Google Drive. 연결 계정이면 주 Drive, 아니면 "다른 계정 업로드" 로 올린 곳
 * - `drive:`(주인 미정 옛 기록) → 주 Drive 로 본다 — 처음 보이는 연결 계정이 가져갈 기록이다
 * - `remote:<id>` → 연결된 저장소의 이름. 저장소를 지웠으면 id 그대로
 */
object BackupDestinations {
    private const val DRIVE_PREFIX = "drive:"
    private const val REMOTE_PREFIX = "remote:"

    fun describe(destination: String, accounts: List<RemoteAccount>, primaryEmail: String?): BackupDestination {
        if (destination.startsWith(DRIVE_PREFIX)) {
            val email = destination.removePrefix(DRIVE_PREFIX)
            return BackupDestination(
                destination = destination,
                remoteName = null,
                driveEmail = email,
                isPrimaryDrive = email.isEmpty() || email.equals(primaryEmail, ignoreCase = true),
            )
        }
        val id = destination.removePrefix(REMOTE_PREFIX)
        return BackupDestination(
            destination = destination,
            remoteName = accounts.firstOrNull { it.id == id }?.displayName ?: id,
            driveEmail = null,
            isPrimaryDrive = false,
        )
    }

    /**
     * 칩·막대의 순서: 주 Drive → 다른 Google 계정 → 다른 저장소(이름순). 같은 곳은 하나로.
     * 주인 미정 옛 기록(`drive:`)은 주 Drive 와 같은 칸으로 합친다.
     */
    fun ordered(
        destinations: Collection<String>,
        accounts: List<RemoteAccount>,
        primaryEmail: String?,
    ): List<BackupDestination> = destinations
        .map { canonical(it, primaryEmail) }
        .distinct()
        .map { describe(it, accounts, primaryEmail) }
        .sortedWith(
            compareBy<BackupDestination>(
                { !it.isPrimaryDrive },
                { !it.isDrive },
                { it.driveEmail ?: it.remoteName.orEmpty() },
            ),
        )

    /** 주인 미정 옛 기록을 연결 계정의 목적지로 — 같은 Drive 를 두 칸으로 세지 않게 */
    fun canonical(destination: String, primaryEmail: String?): String =
        if (destination == DRIVE_PREFIX && primaryEmail != null) "$DRIVE_PREFIX$primaryEmail" else destination
}
