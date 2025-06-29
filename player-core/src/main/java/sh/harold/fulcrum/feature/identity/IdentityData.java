package sh.harold.fulcrum.feature.identity;

import sh.harold.fulcrum.api.data.annotation.Column;
import sh.harold.fulcrum.api.data.annotation.PrimaryKeyGeneration;
import sh.harold.fulcrum.api.data.impl.SchemaVersion;
import sh.harold.fulcrum.api.data.impl.Table;

import java.util.UUID;

@Table("player_identity")
@SchemaVersion(1)
public class IdentityData {

    @Column(primary = true, generation = PrimaryKeyGeneration.PLAYER_UUID)
    public UUID uuid;

    public String displayname;
    public String rank = "DEFAULT";
    public String packageRank = "";
    public String monthlyPackageRank = "";

    public long firstLogin = 0L;
    public long lastLogin = 0L;
    public long lastLogout = 0L;

}