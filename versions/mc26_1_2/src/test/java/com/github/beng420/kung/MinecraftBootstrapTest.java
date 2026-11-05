package com.github.beng420.kung;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;

/** Base for tests that touch Minecraft registries (blocks, items, ClickEvent codecs). */
public abstract class MinecraftBootstrapTest {
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
}
