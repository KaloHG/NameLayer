package vg.civcraft.mc.namelayer.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import vg.civcraft.mc.namelayer.GroupManager;
import vg.civcraft.mc.namelayer.NameAPI;
import vg.civcraft.mc.namelayer.NameLayerPlugin;
import vg.civcraft.mc.namelayer.group.Group;
import vg.civcraft.mc.namelayer.misc.ClassHandler;
import vg.civcraft.mc.namelayer.misc.MaterialInterface;

/**
 * Abstract utility class, which provides some functionality needed for all guis
 *
 */
public abstract class AbstractGroupGUI {
	protected Group g;
	protected Player p;
	protected static GroupManager gm;

	protected static ClassHandler ch;

	protected static MaterialInterface mats;
	
	public AbstractGroupGUI(Group g, Player p) {
		if (gm == null) {
			gm = NameAPI.getGroupManager();
		}
		this.g = g;
		this.p = p;
		matsInit();
	}

	public static void matsInit() {
		if (ch == null) {
			Bukkit.getScheduler().runTaskLater(NameLayerPlugin.getInstance(), new Runnable() {

				@Override
				public void run() {
					ch = ClassHandler.ch;
					if (ClassHandler.properlyEnabled)
						mats = ch.getMaterialClass();
				}

			}, 1);
		}
	}
	
	protected boolean validGroup() {
		if (!g.isValid()) {
			g = GroupManager.getGroup(g.getName());
			if (g == null) {
				return false;
			}
		}
		return true;
	}
	
	public Player getPlayer() {
		return p;
	}
	
	public Group getGroup() {
		return g;
	}
	
	public static ItemStack goBackStack() {
		if (mats != null) {
			ItemStack is = mats.getItemStack(MaterialInterface.Specific.BACK);
			is.setCustomModelData(706);
			return is;
		}
		return new ItemStack(Material.BARRIER); // common for now
	}
	public static ItemStack yesStack() {
		if (mats != null) {
			ItemStack is = mats.getItemStack(MaterialInterface.Specific.GREEN);
			is.setCustomModelData(705);
			return is;
		}
		return new ItemStack(Material.BARRIER); // common for now
	}
	public static ItemStack noStack() {
		if (mats != null) {
			ItemStack is = mats.getItemStack(MaterialInterface.Specific.RED);
			is.setCustomModelData(707);
			return is;
		}
		return new ItemStack(Material.BARRIER); // common for now
	}
	public static ItemStack modStack() {
		if (mats != null) {
			ItemStack is = mats.getItemStack(MaterialInterface.Specific.MOD);
			is.setCustomModelData(708);
			return is;
		}
		return new ItemStack(Material.BARRIER); // common for now
	}
	public Material modMat() {
		if (mats != null) return mats.getMaterial(MaterialInterface.Specific.MOD);
		return Material.BARRIER; // common for now
	}
	public ItemStack blacklistStack() {
		if (mats != null) {
			ItemStack is = mats.getItemStack(MaterialInterface.Specific.BLACKLIST);
			is.setCustomModelData(709);
			return is;
		}
		return new ItemStack(Material.BARRIER); // common for now
	}
	public ItemStack permsStack() {
		if (mats != null) {
			ItemStack is = mats.getItemStack(MaterialInterface.Specific.PERMS);
			is.setCustomModelData(710);
			return is;
		}
		return new ItemStack(Material.BARRIER); // common for now
	}
	public ItemStack mergeStack() {
		if (mats != null) return mats.getItemStack(MaterialInterface.Specific.MERGE);
		return new ItemStack(Material.BARRIER); // common for now
	}
	public ItemStack defaultStack() {
		if (mats != null) {
			ItemStack is = mats.getItemStack(MaterialInterface.Specific.DEFAULT);
			is.setCustomModelData(711);
			return is;
		}
		return new ItemStack(Material.BARRIER); // common for now
	}

}
