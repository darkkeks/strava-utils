package stravahooks.telegram

import stravahooks.storage.ActionDefinition
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow

fun mainMenuMarkup(): InlineKeyboardMarkup {
    val rows = listOf(
        InlineKeyboardRow(
            listOf(
                InlineKeyboardButton.builder().text("Actions").callbackData("actions_menu").build(),
                InlineKeyboardButton.builder().text("Apply").callbackData("apply_menu").build()
            )
        ),
        InlineKeyboardRow(
            listOf(
                InlineKeyboardButton.builder().text("Status").callbackData("status_menu").build(),
                InlineKeyboardButton.builder().text("Logs").callbackData("logs_menu").build()
            )
        ),
        InlineKeyboardRow(
            listOf(
                InlineKeyboardButton.builder().text("Link / Relink").callbackData("link_menu").build()
            )
        )
    )
    return InlineKeyboardMarkup.builder()
        .keyboard(rows)
        .build()
}

fun withMainMenu(markup: InlineKeyboardMarkup?): InlineKeyboardMarkup {
    val rows = markup?.keyboard?.toMutableList() ?: mutableListOf()
    rows.add(
        InlineKeyboardRow(
            listOf(
                InlineKeyboardButton.builder().text("Main menu").callbackData("menu").build()
            )
        )
    )
    return InlineKeyboardMarkup.builder()
        .keyboard(rows)
        .build()
}

fun actionsKeyboard(actions: List<ActionDefinition>): InlineKeyboardMarkup {
    val rows = actions.map { action ->
        InlineKeyboardRow(
            listOf(
                InlineKeyboardButton.builder()
                    .text("Show: ${action.name}")
                    .callbackData("action_show:${action.id}")
                    .build()
            )
        )
    }
    val extra = InlineKeyboardRow(
        listOf(
            InlineKeyboardButton.builder().text("Create action").callbackData("action_create").build(),
            InlineKeyboardButton.builder().text("Main menu").callbackData("menu").build()
        )
    )
    return InlineKeyboardMarkup.builder()
        .keyboard(rows.toMutableList().apply { add(extra) })
        .build()
}

fun actionsListMarkup(actions: List<ActionDefinition>): InlineKeyboardMarkup = actionsKeyboard(actions)

fun actionDetailMarkup(action: ActionDefinition?): InlineKeyboardMarkup? {
    if (action == null) {
        return null
    }
    val toggleText = if (action.enabled) "Disable" else "Enable"
    val row = InlineKeyboardRow(
        listOf(
            InlineKeyboardButton.builder()
                .text("Edit code")
                .callbackData("action_edit:${action.id}")
                .build(),
            InlineKeyboardButton.builder()
                .text(toggleText)
                .callbackData("action_toggle:${action.id}")
                .build(),
            InlineKeyboardButton.builder()
                .text("Check on Activity")
                .callbackData("action_check:${action.id}")
                .build()
        )
    )
    val editDescRow = InlineKeyboardRow(
        listOf(
            InlineKeyboardButton.builder()
                .text("Edit description")
                .callbackData("action_edit_desc:${action.id}")
                .build(),
            InlineKeyboardButton.builder()
                .text("Delete")
                .callbackData("action_delete:${action.id}")
                .build()
        )
    )
    val menuRow = InlineKeyboardRow(
        listOf(
            InlineKeyboardButton.builder()
                .text("Back")
                .callbackData("back_actions")
                .build()
        )
    )
    return InlineKeyboardMarkup.builder()
        .keyboard(listOf(row, editDescRow, menuRow))
        .build()
}

fun actionDeleteMarkup(actionId: String): InlineKeyboardMarkup {
    val row = InlineKeyboardRow(
        listOf(
            InlineKeyboardButton.builder()
                .text("Delete")
                .callbackData("action_delete_confirm:$actionId")
                .build(),
            InlineKeyboardButton.builder()
                .text("Cancel")
                .callbackData("action_delete_cancel:$actionId")
                .build()
        )
    )
    return InlineKeyboardMarkup.builder()
        .keyboard(listOf(row))
        .build()
}
