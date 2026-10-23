package com.github.beng420.kung.config.category;

import com.github.beng420.kung.util.KeyboardLayout;

public final class DebugConfig extends ConfigCategory {
    private boolean enabled = false;
    private boolean contextMessages = true;
    private boolean dungeonMessages = true;
    private boolean interfaceMessages = true;
    private boolean tarantulaMessages = true;
    /** A zone ID such as Europe/Berlin; blank detects it. */
    private String timeZone = "";
    private KeyboardLayout keyboardLayout = KeyboardLayout.AUTO;

    public String timeZone() {
        return timeZone == null ? "" : timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone == null ? "" : timeZone.strip();
        save();
    }

    public KeyboardLayout keyboardLayout() {
        return keyboardLayout == null ? KeyboardLayout.AUTO : keyboardLayout;
    }

    public void setKeyboardLayout(KeyboardLayout keyboardLayout) {
        this.keyboardLayout = keyboardLayout == null ? KeyboardLayout.AUTO : keyboardLayout;
        MiscConfig.forgetLayoutKeybind();
        save();
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    public boolean contextMessages() {
        return contextMessages;
    }

    public void setContextMessages(boolean contextMessages) {
        this.contextMessages = contextMessages;
        save();
    }

    public boolean dungeonMessages() {
        return dungeonMessages;
    }

    public void setDungeonMessages(boolean dungeonMessages) {
        this.dungeonMessages = dungeonMessages;
        save();
    }

    public boolean interfaceMessages() {
        return interfaceMessages;
    }

    public void setInterfaceMessages(boolean interfaceMessages) {
        this.interfaceMessages = interfaceMessages;
        save();
    }

    public boolean contextMessagesEnabled() {
        return enabled && contextMessages;
    }

    public boolean dungeonMessagesEnabled() {
        return enabled && dungeonMessages;
    }

    public boolean interfaceMessagesEnabled() {
        return enabled && interfaceMessages;
    }

    public boolean tarantulaMessagesEnabled() {
        return enabled && tarantulaMessages;
    }

    public boolean tarantulaMessages() { return tarantulaMessages; }
    public void setTarantulaMessages(boolean value) { tarantulaMessages = value; save(); }
}
