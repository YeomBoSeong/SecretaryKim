package com.ybs.assistant.agent

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.ybs.assistant.R
import org.json.JSONObject

private const val MAX_CONTACT_RESULTS = 20

/**
 * 연락처 조회/저장 도구. READ_CONTACTS/WRITE_CONTACTS는 온보딩에서 미리 받지 않고, AI가 해당
 * 도구를 처음 쓰려는 순간 [onRequestPermission]으로 요청한다(ChatViewModel이 시스템 권한
 * 다이얼로그를 띄우고 결과를 돌려준다).
 */
object ContactsToolExecutor {

    suspend fun execute(
        context: Context,
        name: String,
        input: JSONObject,
        onRequestPermission: suspend (String) -> Boolean,
    ): String {
        val permission = if (name == "save_contact") Manifest.permission.WRITE_CONTACTS else Manifest.permission.READ_CONTACTS
        if (!ensurePermission(context, permission, onRequestPermission)) {
            return context.getString(R.string.contacts_permission_denied)
        }
        return when (name) {
            "search_contacts" -> searchContacts(context, input.optString("query", ""))
            "save_contact" -> saveContact(context, input.optString("name", ""), input.optString("phone_number", ""))
            else -> context.getString(R.string.fs_unknown_tool, name)
        }
    }

    private suspend fun ensurePermission(
        context: Context,
        permission: String,
        onRequestPermission: suspend (String) -> Boolean,
    ): Boolean {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return true
        return onRequestPermission(permission)
    }

    private fun searchContacts(context: Context, query: String): String {
        if (query.isBlank()) return context.getString(R.string.contacts_query_required)

        // 이름 하나가 전화번호를 여러 개(집/휴대폰 등) 가질 수 있어 이름별로 묶어서 보여준다.
        val results = LinkedHashMap<String, MutableList<String>>()
        val cursor = runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                null,
            )
        }.getOrNull()

        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext() && results.size < MAX_CONTACT_RESULTS) {
                val contactName = it.getString(nameIdx) ?: continue
                val number = it.getString(numberIdx) ?: continue
                results.getOrPut(contactName) { mutableListOf() }.add(number)
            }
        }

        if (results.isEmpty()) return context.getString(R.string.contacts_no_results, query)
        return results.entries.joinToString("\n") { (contactName, numbers) ->
            "$contactName: ${numbers.distinct().joinToString(", ")}"
        }
    }

    private fun saveContact(context: Context, name: String, phoneNumber: String): String {
        if (name.isBlank()) return context.getString(R.string.contacts_name_required)
        if (phoneNumber.isBlank()) return context.getString(R.string.contacts_phone_required)

        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build(),
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build(),
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build(),
        )

        return runCatching {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            context.getString(R.string.contacts_save_success, name, phoneNumber)
        }.getOrElse { context.getString(R.string.contacts_save_failed, it.message) }
    }
}
