package dev.echoaholic.storage;

import java.util.Optional;
import java.util.UUID;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jspecify.annotations.Nullable;

/**
 * The owner's name and skin as seen at their last join, kept in {@link EchoWorldData} so echoes spawned while the owner
 * is offline still wear the right skin without a profile lookup.
 *
 * @param name the owner's player name (also used in the echo's name tag)
 * @param textures value of the profile's {@value #TEXTURES} property (base64 JSON), or null on offline-mode servers
 * @param signature Mojang's signature of {@code textures}, or null when unsigned or absent
 */
public record OwnerProfile(String name, @Nullable String textures, @Nullable String signature) {
	/** Name of the skin property in a {@link GameProfile}'s property map. */
	public static final String TEXTURES = "textures";

	/** Longest name a {@link GameProfile} may carry over the network ({@code ExtraCodecs.PLAYER_NAME}). */
	private static final int MAX_NAME_LENGTH = 16;

	static final Codec<OwnerProfile> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.optionalFieldOf("name", "").forGetter(OwnerProfile::name),
			Codec.STRING.optionalFieldOf("textures").forGetter(p -> Optional.ofNullable(p.textures)),
			Codec.STRING.optionalFieldOf("signature").forGetter(p -> Optional.ofNullable(p.signature))
	).apply(i, (name, textures, signature) -> new OwnerProfile(name, textures.orElse(null), signature.orElse(null))));

	public OwnerProfile {
		if (name == null) name = "";
		if (textures == null) signature = null;
	}

	/** Copies the name and the first {@value #TEXTURES} property (value + signature) of a live profile. */
	public static OwnerProfile of(GameProfile p) {
		String textures = null;
		String signature = null;
		for (Property property : p.properties().get(TEXTURES)) {
			textures = property.value();
			signature = property.signature();
			break;
		}
		return new OwnerProfile(p.name(), textures, signature);
	}

	/**
	 * Profile component for an echo of {@code id}: fully resolved (skin sent with the entity, no client lookup) when the
	 * textures are known, else unresolved by id so each client looks the skin up itself.
	 */
	public ResolvableProfile toResolvable(UUID id) {
		if (textures == null || !validName(name)) return ResolvableProfile.createUnresolved(id);
		Property property = signature == null ? new Property(TEXTURES, textures) : new Property(TEXTURES, textures, signature);
		PropertyMap properties = new PropertyMap(ImmutableMultimap.of(TEXTURES, property));
		return ResolvableProfile.createResolved(new GameProfile(id, name, properties));
	}

	private static boolean validName(String name) {
		if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) return false;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c <= ' ' || c >= 127) return false;
		}
		return true;
	}
}
