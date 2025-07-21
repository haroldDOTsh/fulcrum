package sh.harold.fulcrum.fundamentals.identity;

import sh.harold.fulcrum.api.data.annotation.*;
import sh.harold.fulcrum.api.data.impl.SchemaVersion;
import sh.harold.fulcrum.api.data.impl.Table;
import sh.harold.fulcrum.api.rank.enums.FunctionalRank;
import sh.harold.fulcrum.api.rank.enums.MonthlyPackageRank;
import sh.harold.fulcrum.api.rank.enums.PackageRank;

import java.util.UUID;

@Table("player_identity")
@SchemaVersion(1)
@Indexes({
        @CompositeIndex(
                name = "idx_package_rank_last_login",
                fields = {"packageRank", "lastLogin"}
        )
})
public class IdentityData {

    @Column(primary = true, generation = PrimaryKeyGeneration.PLAYER_UUID)
    public UUID uuid;

    public String displayname;

    // Enhanced rank fields with proper typing
    @Index(name = "idx_functional_rank")
    public FunctionalRank functionalRank;           // Optional (null = no functional rank)

    @Index(name = "idx_package_rank")
    public PackageRank packageRank = PackageRank.DEFAULT;  // Always has value, defaults to DEFAULT

    @Index(name = "idx_monthly_package_rank")
    public MonthlyPackageRank monthlyPackageRank;   // Optional (null = no monthly rank)

    public long firstLogin = 0L;

    @Index(name = "idx_last_login")
    public long lastLogin = 0L;

    public long lastLogout = 0L;

}