package magicjinn.theblockkeepsticking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Constants {
    /**
     * Resource namespace shared by the mixin config, access widener and logger.
     * Kept underscore-only because resource identifiers cannot contain hyphens.
     */
    public static final String MOD_ID = "the_block_keeps_ticking";

    /**
     * Fabric / Modrinth mod id (hyphens allowed). Kept for publish continuity and
     * for Fabric chunk-attachment namespaces already written to existing worlds.
     */
    public static final String FABRIC_MOD_ID = "the-block-keeps-ticking";

    /**
     * Config filename stored in the Fabric {@code config/} folder. Not stored
     * inside world saves.
     */
    public static final String CONFIG_FILE = FABRIC_MOD_ID + ".json";

    public static final String MOD_NAME = "The Block Keeps Ticking";
    public static final String ENTRYPOINT_KEY = "theblockkeepsticking";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
}
