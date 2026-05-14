package com.amwill.keeb.voice

import com.amwill.keeb.models.ModelInventory

class VoiceInputCoordinator(
    private val inventory: ModelInventory,
    private val controller: VoiceInputController,
) {
    fun startWithSelectedModel(): VoiceState = controller.start(inventory.selectedModel())
}
