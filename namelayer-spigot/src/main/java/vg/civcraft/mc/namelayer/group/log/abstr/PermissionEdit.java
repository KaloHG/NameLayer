package vg.civcraft.mc.namelayer.group.log.abstr;

import java.util.UUID;

import com.google.common.base.Preconditions;

public abstract class PermissionEdit extends MemberRankChange {

	protected final String permission;
	
	public PermissionEdit(long time, UUID player, String rank, String permission) {
		super(time, player, rank);
		Preconditions.checkNotNull(permission);
		this.permission = permission;
	}
	
	public String getPermission() {
		return permission;
	}
}
