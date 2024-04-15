package com.greenart7c3.nostrsigner.ui.components

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.toLowerCase
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.GsonBuilder
import com.greenart7c3.nostrsigner.LocalPreferences
import com.greenart7c3.nostrsigner.R
import com.greenart7c3.nostrsigner.database.AppDatabase
import com.greenart7c3.nostrsigner.models.Account
import com.greenart7c3.nostrsigner.models.IntentData
import com.greenart7c3.nostrsigner.models.Permission
import com.greenart7c3.nostrsigner.models.SignerType
import com.greenart7c3.nostrsigner.service.AmberUtils
import com.greenart7c3.nostrsigner.service.ApplicationNameCache
import com.greenart7c3.nostrsigner.service.EventNotificationConsumer
import com.greenart7c3.nostrsigner.service.IntentUtils
import com.greenart7c3.nostrsigner.service.getAppCompatActivity
import com.greenart7c3.nostrsigner.service.model.AmberEvent
import com.greenart7c3.nostrsigner.service.toShortenHex
import com.greenart7c3.nostrsigner.ui.BunkerResponse
import com.greenart7c3.nostrsigner.ui.Result
import com.greenart7c3.nostrsigner.ui.theme.ButtonBorder
import com.vitorpamplona.quartz.events.Event
import com.vitorpamplona.quartz.events.LnZapRequestEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun MultiEventHomeScreen(
    intents: List<IntentData>,
    packageName: String?,
    accountParam: Account,
    database: AppDatabase,
    onLoading: (Boolean) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        Modifier.fillMaxSize()
    ) {
        var selectAll by remember {
            mutableStateOf(false)
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .clickable {
                    selectAll = !selectAll
                    intents.forEach {
                        it.checked.value = selectAll
                    }
                }
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.select_deselect_all)
            )
            Switch(
                checked = selectAll,
                onCheckedChange = {
                    selectAll = !selectAll
                    intents.forEach {
                        it.checked.value = selectAll
                    }
                }
            )
        }
        LazyColumn(
            Modifier.fillMaxHeight(0.9f)
        ) {
            items(intents.size) {
                ListItem(
                    intents[it],
                    packageName,
                    accountParam,
                    intents
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            Arrangement.Center
        ) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                shape = ButtonBorder,
                onClick = {
                    onLoading(true)
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val activity = context.getAppCompatActivity()
                            val results = mutableListOf<Result>()

                            for (intentData in intents) {
                                val localAccount =
                                    if (intentData.currentAccount.isNotBlank()) {
                                        LocalPreferences.loadFromEncryptedStorage(
                                            intentData.currentAccount
                                        )
                                    } else {
                                        accountParam
                                    } ?: continue

                                val key = intentData.bunkerRequest?.localKey ?: packageName ?: continue
                                val applicationEntity = database.applicationDao().getByKey(key)

                                if (intentData.type == SignerType.SIGN_EVENT) {
                                    val localEvent = try {
                                        Event.fromJson(intentData.data)
                                    } catch (e: Exception) {
                                        Event.fromJson(
                                            IntentUtils.getIntent(
                                                intentData.data,
                                                localAccount.keyPair
                                            ).toJson()
                                        )
                                    }

                                    if (intentData.rememberMyChoice.value) {
                                        AmberUtils.acceptOrRejectPermission(
                                            key,
                                            intentData,
                                            localEvent.kind,
                                            intentData.rememberMyChoice.value,
                                            applicationEntity?.application?.name?.ifBlank { applicationEntity.application.key.toShortenHex() } ?: "",
                                            localAccount,
                                            database
                                        )
                                    }

                                    localAccount.signer.sign<Event>(
                                        localEvent.createdAt,
                                        localEvent.kind,
                                        localEvent.tags,
                                        localEvent.content
                                    ) { signedEvent ->
                                        if (intentData.bunkerRequest != null) {
                                            IntentUtils.sendBunkerResponse(
                                                localAccount,
                                                intentData.bunkerRequest.localKey,
                                                BunkerResponse(intentData.bunkerRequest.id, signedEvent.toJson(), null),
                                                applicationEntity?.application?.relays ?: listOf("wss://relay.nsec.app"),
                                                onLoading = {}
                                            ) {}
                                        } else {
                                            results.add(
                                                Result(
                                                    null,
                                                    if (localEvent is LnZapRequestEvent && localEvent.tags.any { tag -> tag.any { t -> t == "anon" } }) signedEvent.toJson() else signedEvent.sig,
                                                    intentData.id
                                                )
                                            )
                                        }
                                    }
                                } else {
                                    if (intentData.rememberMyChoice.value) {
                                        AmberUtils.acceptOrRejectPermission(
                                            key,
                                            intentData,
                                            null,
                                            intentData.rememberMyChoice.value,
                                            applicationEntity?.application?.name?.ifBlank { applicationEntity.application.key.toShortenHex() } ?: "",
                                            localAccount,
                                            database
                                        )
                                    }
                                    val signature = AmberUtils.encryptOrDecryptData(
                                        intentData.data,
                                        intentData.type,
                                        localAccount,
                                        intentData.pubKey
                                    ) ?: continue

                                    if (intentData.bunkerRequest != null) {
                                        IntentUtils.sendBunkerResponse(
                                            localAccount,
                                            intentData.bunkerRequest.localKey,
                                            BunkerResponse(intentData.bunkerRequest.id, signature, null),
                                            applicationEntity?.application?.relays ?: listOf("wss://relay.nsec.app"),
                                            onLoading = {}
                                        ) { }
                                    } else {
                                        results.add(
                                            Result(
                                                null,
                                                signature,
                                                intentData.id
                                            )
                                        )
                                    }
                                }
                            }

                            if (results.isNotEmpty()) {
                                val gson = GsonBuilder().serializeNulls().create()
                                val json = gson.toJson(results)
                                val intent = Intent()
                                intent.putExtra("results", json)
                                activity?.setResult(Activity.RESULT_OK, intent)
                            }
                            if (intents.any { it.bunkerRequest != null }) {
                                EventNotificationConsumer(context).notificationManager().cancelAll()
                                activity?.intent = null
                                activity?.finish()
                            } else {
                                activity?.intent = null
                                activity?.finish()
                            }
                        } finally {
                            onLoading(false)
                        }
                    }
                }
            ) {
                Text("Confirm")
            }
        }
    }
}

@Composable
fun ListItem(
    intentData: IntentData,
    packageName: String?,
    accountParam: Account,
    intents: List<IntentData>
) {
    var isExpanded by remember { mutableStateOf(false) }

    val key = if (intentData.bunkerRequest != null) {
        intentData.bunkerRequest.localKey
    } else {
        "$packageName"
    }

    val appName = ApplicationNameCache.names[key] ?: key.toShortenHex()

    Card(
        Modifier
            .padding(4.dp)
            .clickable {
                isExpanded = !isExpanded
            }
    ) {
        val name = LocalPreferences.getAccountName(intentData.currentAccount)
        Row(
            Modifier
                .fillMaxWidth(),
            Arrangement.Center,
            Alignment.CenterVertically
        ) {
            Text(
                name.ifBlank { intentData.currentAccount.toShortenHex() },
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            Icon(
                Icons.Default.run {
                    if (isExpanded) {
                        KeyboardArrowDown
                    } else {
                        KeyboardArrowUp
                    }
                },
                contentDescription = "",
                tint = Color.LightGray
            )

            val text = if (intentData.type == SignerType.SIGN_EVENT) {
                val event = IntentUtils.getIntent(intentData.data, accountParam.keyPair)
                val permission = Permission("sign_event", event.kind)
                "wants you to sign a $permission"
            } else {
                val permission = Permission(intentData.type.toString().toLowerCase(Locale.current), null)
                "wants you to $permission"
            }
            Text(
                modifier = Modifier.weight(1f),
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(appName)
                    }
                    append(" $text")
                },
                fontSize = 18.sp
            )

            Switch(
                checked = intentData.checked.value,
                onCheckedChange = { _ ->
                    intentData.checked.value = !intentData.checked.value
                }
            )
        }

        if (isExpanded) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(10.dp)
            ) {
                Text(
                    "Event content",
                    fontWeight = FontWeight.Bold
                )
                val content = if (intentData.type == SignerType.SIGN_EVENT) {
                    val event = IntentUtils.getIntent(
                        intentData.data,
                        accountParam.keyPair
                    )
                    if (event.kind == 22242) AmberEvent.relay(event) else event.content
                } else {
                    intentData.data
                }

                Text(
                    content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                RememberMyChoice(
                    shouldRunOnAccept = false,
                    intentData.rememberMyChoice.value,
                    appName,
                    { }
                ) {
                    intentData.rememberMyChoice.value = !intentData.rememberMyChoice.value
                    intents.filter { item ->
                        intentData.type == item.type
                    }.forEach { item ->
                        item.rememberMyChoice.value = intentData.rememberMyChoice.value
                    }
                }
            }
        }
    }
}
