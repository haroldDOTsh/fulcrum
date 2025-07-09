package sh.harold.fulcrum.fundamentals.identity;

import sh.harold.fulcrum.api.data.annotation.Column;
import sh.harold.fulcrum.api.data.annotation.PrimaryKeyGeneration;
import sh.harold.fulcrum.api.data.impl.SchemaVersion;
import sh.harold.fulcrum.api.data.impl.Table;
import sh.harold.fulcrum.api.rank.enums.FunctionalRank;
import sh.harold.fulcrum.api.rank.enums.MonthlyPackageRank;
import sh.harold.fulcrum.api.rank.enums.PackageRank;

import java.util.UUID;

@Table("player_identity")
@SchemaVersion(1)
public class IdentityData {

    @Column(primary = true, generation = PrimaryKeyGeneration.PLAYER_UUID)
    public UUID uuid;

    public String displayname;
    
    // Enhanced rank fields with proper typing
    public FunctionalRank functionalRank;           // Optional (null = no functional rank)
    public PackageRank packageRank = PackageRank.DEFAULT;  // Always has value, defaults to DEFAULT
    public MonthlyPackageRank monthlyPackageRank;   // Optional (null = no monthly rank)

    public long firstLogin = 0L;
    public long lastLogin = 0L;
    public long lastLogout = 0L;

}