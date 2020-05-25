package vg.civcraft.mc.namelayer.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import vg.civcraft.mc.civmodcore.dao.ManagedDatasource;
import vg.civcraft.mc.namelayer.GroupAPI;
import vg.civcraft.mc.namelayer.GroupManager;
import vg.civcraft.mc.namelayer.NameLayerPlugin;
import vg.civcraft.mc.namelayer.group.Group;
import vg.civcraft.mc.namelayer.group.GroupLink;
import vg.civcraft.mc.namelayer.group.NameLayerMetaData;
import vg.civcraft.mc.namelayer.group.log.LoggedGroupActionPersistence;
import vg.civcraft.mc.namelayer.group.log.abstr.LoggedGroupAction;
import vg.civcraft.mc.namelayer.group.meta.GroupMetaDataAPI;
import vg.civcraft.mc.namelayer.listeners.PlayerListener;
import vg.civcraft.mc.namelayer.permission.PermissionType;
import vg.civcraft.mc.namelayer.permission.PlayerType;
import vg.civcraft.mc.namelayer.permission.PlayerTypeHandler;

public class GroupManagerDao {
	private Logger logger;
	private ManagedDatasource db;

	public GroupManagerDao(NameLayerPlugin plugin, ManagedDatasource db) {
		this.logger = plugin.getLogger();
		this.db = db;
		registerMigrations();
	}

	private void registerMigrations() {
		// TODO initial table creation

		db.registerMigration(14, false, "DELETE FROM permissionByGroup " + "WHERE role='NOT_BLACKLISTED' "
				+ "AND perm_id=(SELECT perm_id FROM permissionIdMapping WHERE name='BASTION_PLACE');");
		db.registerMigration(15, false,
				// Previously if a group was merged, it had multiple ids assigned to it and the
				// "real id" was the smallest id. We fix that here by letting groups only have
				// one id and extracting alternative ids into a separate table
				"create table if not exists mergedGroups (oldGroup int not null, newGroup int not null, primary key(oldGroup))",
				"insert into mergedGroups (oldGroup,newGroup) (select fi1.group_id, fi2.min_id from faction_id fi1 "
						+ "inner join (select min(group_id) as min_id, group_name as name from faction_id group by group_name) fi2 "
						+ "on fi2.name = fi1.group_name where fi2.min_id < fi1.group_id)",
				// now we just clean up all duplicates entries for which we have replacements in
				// mergeGroups
				"delete from faction_id where group_id in (select oldGroup from mergedGroups)",
				// delete completely broken groups without id
				"delete from faction where group_name not in (select group_name from faction_id)");

		// now with group meta data and only a single id per group, we don't need both
		// the faction and faction_id table anymore, so we get rid of faction
		db.registerMigration(16, false, () -> {
			Map<String, NameLayerMetaData> retrievedMetas = new HashMap<>();
			try (Connection connection = db.getConnection();
					PreparedStatement getAllMembers = connection
							.prepareStatement("select group_name, founder, password, last_timestamp from faction");
					ResultSet rs = getAllMembers.executeQuery()) {
				while (rs.next()) {
					String name = rs.getString(1);
					String creator = rs.getString(2);
					String password = rs.getString(3);
					long time = rs.getTimestamp(4).getTime();
					NameLayerMetaData meta = NameLayerMetaData.createNew();
					meta.setCreator(UUID.fromString(creator));
					if (password != null) {
						meta.setPassword(password);
					}
					meta.setLastRefresh(time);
					retrievedMetas.put(name, meta);
				}
			} catch (SQLException e) {
				logger.log(Level.SEVERE, "Failed to load group meta data", e);
				return false;
			}
			try (Connection connection = db.getConnection();
					PreparedStatement insertMeta = connection
							.prepareStatement("update faction_id set meta_data = ? where group_name = ?")) {
				for (Entry<String, NameLayerMetaData> entry : retrievedMetas.entrySet()) {
					JsonObject json = new JsonObject();
					entry.getValue().serialize(json);
					insertMeta.setString(1, json.toString());
					insertMeta.setString(2, entry.getKey());
					insertMeta.addBatch();
				}
				insertMeta.executeBatch();
			} catch (SQLException e) {
				logger.log(Level.SEVERE, "Problem inserting new meta data", e);
				return false;
			}
			return true;
		}, "alter table faction_id add column meta_data text default null;");

		db.registerMigration(17, false,
				// leftover cleanup from migration 13
				"drop table if exists permissions;",
				// we no longer use this table, so might as well get rid of it
				"drop table if exists nameLayerNameChanges;",
				// make new table to hold player types
				"create table if not exists groupPlayerTypes(group_id int not null,"
						+ "rank_id int not null, type_name varchar(40) not null, parent_rank_id int, constraint unique (group_id, rank_id), constraint unique (group_id, type_name), "
						+ "primary key(group_id,rank_id), foreign key(group_id) references faction_id (group_id) on delete cascade);",
				// convert old player types over to new format
				"insert into groupPlayerTypes (group_id,rank_id,type_name) select group_id, 0, 'OWNER' from faction_id;",
				"insert into groupPlayerTypes (group_id,rank_id,type_name,parent_rank_id) select group_id, 1, 'ADMINS',0 from faction_id;",
				"insert into groupPlayerTypes (group_id,rank_id,type_name,parent_rank_id) select group_id, 2, 'MODS',1 from faction_id;",
				"insert into groupPlayerTypes (group_id,rank_id,type_name,parent_rank_id) select group_id, 3, 'MEMBERS',2 from faction_id;",
				"insert into groupPlayerTypes (group_id,rank_id,type_name,parent_rank_id) select group_id, 4, 'DEFAULT',0 from faction_id;",
				"insert into groupPlayerTypes (group_id,rank_id,type_name,parent_rank_id) select group_id, 5, 'BLACKLISTED',4 from faction_id;",
				// previously permissions were assigned to player ranks by the static name of
				// that type, so we need to change that into an id. First of all we
				// need to get rid of the old primary key, which was a (group_id,role,perm_id)
				// combo
				"alter table permissionByGroup drop primary key;",
				// add the new column we need to track specific player types for a group
				"alter table permissionByGroup add rank_id int default null;",
				// convert old permissions over
				"update permissionByGroup set rank_id=0 where role='OWNER';",
				"update permissionByGroup set rank_id=1 where role='ADMINS';",
				"update permissionByGroup set rank_id=2 where role='MODS';",
				"update permissionByGroup set rank_id=3 where role='MEMBERS';",
				"update permissionByGroup set rank_id=4 where role='NOT_BLACKLISTED';",
				// maybe some broken entries exist, we make sure to clean those out
				"delete from permissionByGroup where rank_id IS NULL;",
				// also delete entries which dont refer to an existing group
				"delete from permissionByGroup where group_id not in (select group_id from faction_id);",
				// now we no longer need the varchar role column
				"alter table permissionByGroup drop column role;",
				// this should have been done in the previous upgrade, it ensures a proper
				// cleanup if someone messes with the master perm table
				"alter table permissionByGroup add constraint foreign key (perm_id) references permissionIdMapping(perm_id) on delete cascade;",
				// ensure perms clean themselves up if the group is deleted
				"alter table permissionByGroup add constraint foreign key (group_id, rank_id) references groupPlayerTypes(group_id, rank_id) on delete cascade;",
				// ensure perms cant be inserted multiple times
				"alter table permissionByGroup add constraint unique (group_id, rank_id, perm_id)",

				// interaction of ranks works differently now, so we need to adjust permissions
				// for that
				// at this point the new invite/remove permissions were already created and
				// registered, we can just use them
				"insert into permissionByGroup (group_id,perm_id,rank_id) select pbg.group_id, pimn.perm_id, pbg.rank_id from permissionByGroup pbg inner join permissionIdMapping pim "
						+ "on pim.perm_id = pbg.perm_id cross join permissionidmapping pimn where pim.name = 'MEMBERS' and pimn.name = 'invitePlayer#3';",
				"insert into permissionByGroup (group_id,perm_id,rank_id) select pbg.group_id, pimn.perm_id, pbg.rank_id from permissionByGroup pbg inner join permissionIdMapping pim "
						+ "on pim.perm_id = pbg.perm_id cross join permissionidmapping pimn where pim.name = 'MEMBERS' and pimn.name = 'removePlayer#3';",
				"insert into permissionByGroup (group_id,perm_id,rank_id) select pbg.group_id, pimn.perm_id, pbg.rank_id from permissionByGroup pbg inner join permissionIdMapping pim "
						+ "on pim.perm_id = pbg.perm_id cross join permissionidmapping pimn where pim.name = 'MEMBERS' and pimn.name = 'listPlayer#3';",
				"insert into permissionByGroup (group_id,perm_id,rank_id) select pbg.group_id, pimn.perm_id, pbg.rank_id from permissionByGroup pbg inner join permissionIdMapping pim "
						+ "on pim.perm_id = pbg.perm_id cross join permissionidmapping pimn where pim.name = 'MODS' and pimn.name = 'invitePlayer#2';",
				"insert into permissionByGroup (group_id,perm_id,rank_id) select pbg.group_id, pimn.perm_id, pbg.rank_id from permissionByGroup pbg inner join permissionIdMapping pim "
						+ "on pim.perm_id = pbg.perm_id cross join permissionidmapping pimn where pim.name = 'MODS' and pimn.name = 'removePlayer#2';",
				"insert into permissionByGroup (group_id,perm_id,rank_id) select pbg.group_id, pimn.perm_id, pbg.rank_id from permissionByGroup pbg inner join permissionIdMapping pim "
						+ "on pim.perm_id = pbg.perm_id cross join permissionidmapping pimn where pim.name = 'MODS' and pimn.name = 'listPlayer#2';",
				"insert into permissionByGroup (group_id,perm_id,rank_id) select pbg.group_id, pimn.perm_id, pbg.rank_id from permissionByGroup pbg inner join permissionIdMapping pim "
						+ "on pim.perm_id = pbg.perm_id cross join permissionidmapping pimn where pim.name = 'ADMINS' and pimn.name = 'invitePlayer#1';",
				"insert into permissionByGroup (group_id,perm_id,rank_id) select pbg.group_id, pimn.perm_id, pbg.rank_id from permissionByGroup pbg inner join permissionIdMapping pim "
						+ "on pim.perm_id = pbg.perm_id cross join permissionidmapping pimn where pim.name = 'ADMINS' and pimn.name = 'removePlayer#1';",
				"insert into permissionByGroup (group_id,perm_id,rank_id) select pbg.group_id, pimn.perm_id, pbg.rank_id from permissionByGroup pbg inner join permissionIdMapping pim "
						+ "on pim.perm_id = pbg.perm_id cross join permissionidmapping pimn where pim.name = 'ADMINS' and pimn.name = 'listPlayer#1';",
				"insert into permissionByGroup (group_id,perm_id,rank_id) select pbg.group_id, pimn.perm_id, pbg.rank_id from permissionByGroup pbg inner join permissionIdMapping pim "
						+ "on pim.perm_id = pbg.perm_id cross join permissionidmapping pimn where pim.name = 'OWNER' and pimn.name = 'invitePlayer#0';",
				"insert into permissionByGroup (group_id,perm_id,rank_id) select pbg.group_id, pimn.perm_id, pbg.rank_id from permissionByGroup pbg inner join permissionIdMapping pim "
						+ "on pim.perm_id = pbg.perm_id cross join permissionidmapping pimn where pim.name = 'OWNER' and pimn.name = 'removePlayer#0';",
				"insert into permissionByGroup (group_id,perm_id,rank_id) select pbg.group_id, pimn.perm_id, pbg.rank_id from permissionByGroup pbg inner join permissionIdMapping pim "
						+ "on pim.perm_id = pbg.perm_id cross join permissionidmapping pimn where pim.name = 'OWNER' and pimn.name = 'listPlayer#0';",
				"insert into permissionByGroup (group_id,perm_id,rank_id) select pbg.group_id, pimn.perm_id, pbg.rank_id from permissionByGroup pbg inner join permissionIdMapping pim "
						+ "on pim.perm_id = pbg.perm_id cross join permissionidmapping pimn where pim.name = 'BLACKLIST' and pimn.name = 'invitePlayer#5';",
				"insert into permissionByGroup (group_id,perm_id,rank_id) select pbg.group_id, pimn.perm_id, pbg.rank_id from permissionByGroup pbg inner join permissionIdMapping pim "
						+ "on pim.perm_id = pbg.perm_id cross join permissionidmapping pimn where pim.name = 'BLACKLIST' and pimn.name = 'removePlayer#5';",
				"insert into permissionByGroup (group_id,perm_id,rank_id) select pbg.group_id, pimn.perm_id, pbg.rank_id from permissionByGroup pbg inner join permissionIdMapping pim "
						+ "on pim.perm_id = pbg.perm_id cross join permissionidmapping pimn where pim.name = 'BLACKLIST' and pimn.name = 'listPlayer#5';",

				// we are done converting permissions over, so we can now delete the old ones,
				// foreign keys will leftover perms for groups
				"delete from permissionIdMapping where name in ('MEMBERS','MODS','ADMINS','OWNER','BLACKLIST')",

				// now we basically repeat the same for the group members table (faction_member)
				// and invitation table (group_invitation)
				// lets start with group members
				"alter table faction_member add rank_id int default null;",
				"update faction_member set rank_id=0 where role='OWNER';",
				"update faction_member set rank_id=1 where role='ADMINS';",
				"update faction_member set rank_id=2 where role='MODS';",
				"update faction_member set rank_id=3 where role='MEMBERS';",
				"update faction_member set rank_id=4 where role='NOT_BLACKLISTED';",
				"delete from faction_member where rank_id is null;", "alter table faction_member drop column role;",
				"delete from faction_member where group_id not in (select group_id from faction_id);",
				"alter table faction_member add constraint foreign key (group_id, rank_id) references groupPlayerTypes(group_id, rank_id) on delete cascade;",
				"delete from faction_member where member_name is null",

				// remove old restrictions
				"alter table group_invitation drop index `UQ_uuid_groupName`;",

				// first of all use group id instead of name
				"alter table group_invitation add group_id int;",
				"update group_invitation as gi inner join faction_id as fi on fi.group_name=gi.groupName set gi.group_id = fi.group_id;",
				"delete from group_invitation where group_id is null;",
				"alter table group_invitation drop column groupName;",
				// make column not null now that its filled
				"alter table group_invitation alter column group_id int not null",

				// now apply our player type changes
				"alter table group_invitation add rank_id int;",
				"update group_invitation set rank_id=0 where role='OWNER';",
				"update group_invitation set rank_id=1 where role='ADMINS';",
				"update group_invitation set rank_id=2 where role='MODS';",
				"update group_invitation set rank_id=3 where role='MEMBERS';",
				"delete from group_invitation where rank_id is null;", "alter table group_invitation drop column role;",
				"alter table group_invitation add constraint foreign key (group_id, rank_id) references groupPlayerTypes(group_id, rank_id) on delete cascade;",
				"alter table group_invitation add constraint unique (group_id, uuid);",

				// finally easy lookup by group id is nice
				"create index inviteTypeIdIndex on group_invitation(group_id);",
				// drop group deletion, its just merging now
				"drop procedure if exists deletegroupfromtable;",

				// need to update the merging procedure
				"drop procedure if exists mergeintogroup;", "create definer=current_user procedure mergeintogroup("
						+ "in remainingId int, in tomergeId int) " + "sql security invoker begin " +
						// update preexisting merge mapping to the group which we now remove
						"update mergedGroups set newGroup = remainingId where newGroup = tomergeId;"
						+ "insert into mergedGroups (oldGroup,newGroup) values(tomergeId, remainingId);"
						+ "delete from faction_id where group_id = tomergeId;" + "end;",
				// foreign keys will do everything else
				// need to update group creation as well
				"drop procedure if exists createGroup;",
				"create definer=current_user procedure createGroup(in group_name varchar(255)) "
						+ "sql security invoker begin"
						+ " if (select (count(*) = 0) from faction_id q where q.group_name = group_name) is true then"
						+ "  insert into faction_id(group_name) values (group_name); "
						+ "  insert into faction_member (member_name, rank_id, group_id) select founder, 0, f.group_id from faction_id f where f.group_name = group_name; "
						+ "  insert into groupPlayerTypes (group_id, rank_id, type_name, parent_rank_id) select f.group_id, 0, 'OWNER', null from faction_id f where f.group_name = group_name;"
						+ "  insert into groupPlayerTypes (group_id, rank_id, type_name, parent_rank_id) select f.group_id, 1, 'ADMINS', 0 from faction_id f where f.group_name = group_name;"
						+ "  insert into groupPlayerTypes (group_id, rank_id, type_name, parent_rank_id) select f.group_id, 2, 'MODS', null from faction_id f where f.group_name = group_name;"
						+ "  insert into groupPlayerTypes (group_id, rank_id, type_name, parent_rank_id) select f.group_id, 3, 'MEMBERS', null from faction_id f where f.group_name = group_name;"
						+ "  insert into groupPlayerTypes (group_id, rank_id, type_name, parent_rank_id) select f.group_id, 4, 'DEFAULT', 0 from faction_id f where f.group_name = group_name;"
						+ "  insert into groupPlayerTypes (group_id, rank_id, type_name, parent_rank_id) select f.group_id, 5, 'BLACKLISTED', 4 from faction_id f where f.group_name = group_name;"
						+ " end if; " + "end;",
				// add tables for new group linking
				// old one is broken af and worked differently, just get rid of it
				"drop table if exists subgroup",
				"create table nl_group_links (link_id int not null primary key auto_increment, "
						+ "originating_group_id int not null, originating_type_id int not null, target_group_id int not null, "
						+ "target_type_id int not null, foreign key (originating_group_id, originating_type_id) references groupPlayerTypes(group_id, rank_id) on delete cascade,"
						+ "foreign key (target_group_id, target_type_id) references groupPlayerTypes(group_id, rank_id) on delete cascade, "
						+ "unique (originating_group_id, originating_type_id, target_group_id, target_type_id), index(originating_group_id), index(target_group_id))",
				"create table if not exists nl_action_log (action_id int not null primary key auto_increment, type_id int not null,"
						+ "player varchar(36) not null, group_id int not null references faction_id(group_id) on delete cascade, "
						+ "time datetime not null default current_timestamp, rank varchar(255) not null, name varchar(255) default null,"
						+ "extra text default null)");
	}

	public void insertActionLog(Group group, LoggedGroupAction change) {
		try (Connection connection = db.getConnection();
				PreparedStatement addLog = connection.prepareStatement(
						"insert into nl_group_links(type_id, player, group_id, time,rank, name, extra) values(?,?,?,?,?,?,?)")) {
			LoggedGroupActionPersistence persist = change.getPersistence();
			addLog.setInt(1, 0); // TODO
			addLog.setString(2, persist.getPlayer().toString());
			addLog.setInt(3, group.getGroupId());
			addLog.setTimestamp(4, new Timestamp(persist.getTimeStamp()));
			addLog.setString(5, persist.getRank());
			addLog.setString(6, persist.getName());
			addLog.setString(7, persist.getExtraText());
			addLog.execute();
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem inserting log", e);
		}
	}

	public int createGroup(String group) {
		try (Connection connection = db.getConnection();
				PreparedStatement createGroup = connection.prepareStatement("call createGroup(?)")) {
			createGroup.setString(1, group);
			createGroup.execute();
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem setting up query to create group " + group, e);
			return -1;
		}
		try (Connection connection = db.getConnection();
				PreparedStatement getGroup = connection
						.prepareStatement("select group_id from faction_id where group_name=?")) {
			getGroup.setString(1, group);
			try (ResultSet rs = getGroup.executeQuery()) {
				if (rs.next()) {
					return rs.getInt(1);
				}
				return -1;
			}
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem setting up query to create group " + group, e);
			return -1;
		}
	}

	public void renameGroup(String oldName, String newName) {
		try (Connection connection = db.getConnection();
				PreparedStatement renameGroup = connection
						.prepareStatement("update faction_id set group_name = ? where group_name = ?")) {
			renameGroup.setString(1, newName);
			renameGroup.setString(2, oldName);
			renameGroup.execute();
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem updating groupname", e);
		}
	}

	public void addMember(UUID member, Group group, PlayerType role) {
		try (Connection connection = db.getConnection();
				PreparedStatement addMember = connection
						.prepareStatement("insert into faction_member(group_id, rank_id, member_name) values(?,?,?)")) {
			addMember.setInt(1, group.getGroupId());
			addMember.setInt(2, role.getId());
			addMember.setString(3, member.toString());
			addMember.execute();
		} catch (SQLException e) {
			logger.log(Level.WARNING,
					"Problem adding " + member + " as " + role.toString() + " to group " + group.getName(), e);
		}
	}

	public void updateMember(UUID member, Group group, PlayerType role) {
		try (Connection connection = db.getConnection();
				PreparedStatement updateMember = connection.prepareStatement(
						"update faction_member set rank_id = ? where group_id = ? and member_name = ?")) {
			updateMember.setInt(1, role.getId());
			updateMember.setInt(2, group.getGroupId());
			updateMember.setString(3, member.toString());
			updateMember.execute();
		} catch (SQLException e) {
			logger.log(Level.WARNING,
					"Problem updating " + member + " as " + role.toString() + " for group " + group.getName(), e);
		}
	}

	public void removeMember(UUID member, Group group) {
		try (Connection connection = db.getConnection();
				PreparedStatement removeMember = connection
						.prepareStatement("delete from faction_member where member_name = ? and group_id = ?")) {
			removeMember.setString(1, member.toString());
			removeMember.setInt(2, group.getGroupId());
			removeMember.execute();
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem removing " + member + " from group " + group, e);
		}
	}

	public void addAllPermissions(int groupId, Map<PlayerType, List<PermissionType>> perms) {
		try (Connection connection = db.getConnection();
				PreparedStatement addPermissionById = connection
						.prepareStatement("insert into permissionByGroup(group_id,rank_id,perm_id) values(?, ?, ?)")) {
			for (Entry<PlayerType, List<PermissionType>> entry : perms.entrySet()) {
				int typeId = entry.getKey().getId();
				for (PermissionType perm : entry.getValue()) {
					addPermissionById.setInt(1, groupId);
					addPermissionById.setInt(2, typeId);
					addPermissionById.setInt(3, perm.getId());
					addPermissionById.addBatch();
				}
			}

			int[] res = addPermissionById.executeBatch();
			if (res == null) {
				logger.log(Level.WARNING, "Failed to add all permissions to group {0}", groupId);
			} else {
				int count = 0;
				for (int r : res) {
					count += r;
				}
				logger.log(Level.INFO, "Added {0} of {1} permissions to group {2}",
						new Object[] { count, res.length, groupId });
			}
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem adding all permissions to group " + groupId, e);
		}
	}

	public void removeAllPermissions(Group g, Map<PlayerType, List<PermissionType>> perms) {
		int groupId = g.getGroupId();
		try (Connection connection = db.getConnection();
				PreparedStatement removePermissionById = connection.prepareStatement(
						"delete from permissionByGroup where group_id = ? and rank_id = ? and perm_id = ?")) {
			for (Entry<PlayerType, List<PermissionType>> entry : perms.entrySet()) {
				int typeId = entry.getKey().getId();
				for (PermissionType perm : entry.getValue()) {
					removePermissionById.setInt(1, groupId);
					removePermissionById.setInt(2, typeId);
					removePermissionById.setInt(3, perm.getId());
					removePermissionById.addBatch();
				}
			}

			int[] res = removePermissionById.executeBatch();
			if (res == null) {
				logger.log(Level.WARNING, "Failed to remove all permissions from group {0}", groupId);
			} else {
				int cnt = 0;
				for (int r : res)
					cnt += r;
				logger.log(Level.INFO, "Removed {0} of {1} permissions from group {2}",
						new Object[] { cnt, res.length, groupId });
			}
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem removing all permissions from group " + groupId, e);
		}
	}

	public void addPermission(Group group, PlayerType type, PermissionType perm) {
		try (Connection connection = db.getConnection();
				PreparedStatement addPermission = connection
						.prepareStatement("insert into permissionByGroup(group_id,rank_id,perm_id) values(?, ?, ?)")) {
			addPermission.setInt(1, group.getGroupId());
			addPermission.setInt(2, type.getId());
			addPermission.setInt(3, perm.getId());
			addPermission.execute();
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem adding " + type + " with " + perm + " to group " + group.getName(), e);
		}
	}

	public Map<PlayerType, List<PermissionType>> getPermissions(Group group) {
		Map<PlayerType, List<PermissionType>> perms = new HashMap<>();
		try (Connection connection = db.getConnection();
				PreparedStatement getPermission = connection
						.prepareStatement("select rank_id, perm_id from permissionByGroup where group_id = ?")) {
			getPermission.setInt(1, group.getGroupId());
			PlayerTypeHandler handler = group.getPlayerTypeHandler();
			try (ResultSet set = getPermission.executeQuery();) {
				while (set.next()) {
					PlayerType type = handler.getType(set.getInt(1));
					List<PermissionType> listPerm = perms.get(type);
					if (listPerm == null) {
						listPerm = new ArrayList<>();
						perms.put(type, listPerm);
					}
					int id = set.getInt(2);
					PermissionType perm = PermissionType.getPermission(id);
					if (perm != null && !listPerm.contains(perm)) {
						listPerm.add(perm);
					}
				}
			}
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem getting permissions for group " + group, e);
		}
		return perms;
	}

	public void removePermission(Group group, PlayerType pType, PermissionType perm) {
		try (Connection connection = db.getConnection();
				PreparedStatement removePermission = connection.prepareStatement(
						"delete from permissionByGroup where group_id = ? and rank_id = ? and perm_id = ?")) {
			removePermission.setInt(1, group.getGroupId());
			removePermission.setInt(2, pType.getId());
			removePermission.setInt(3, perm.getId());
			removePermission.executeUpdate();
		} catch (SQLException e) {
			logger.log(Level.WARNING,
					"Problem removing permissions for group " + group + " on playertype " + pType.getName(), e);
		}
	}

	public void registerPermission(PermissionType perm) {
		try (Connection connection = db.getConnection();
				PreparedStatement registerPermission = connection
						.prepareStatement("insert into permissionIdMapping(perm_id, name) values(?, ?)")) {
			registerPermission.setInt(1, perm.getId());
			registerPermission.setString(2, perm.getName());
			registerPermission.executeUpdate();
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem register permission " + perm.getName(), e);
		}
	}

	public Map<Integer, String> getPermissionMapping() {
		Map<Integer, String> perms = new TreeMap<>();
		try (Connection connection = db.getConnection();
				Statement getPermissionMapping = connection.createStatement()) {
			try (ResultSet res = getPermissionMapping.executeQuery("select * from permissionIdMapping")) {
				while (res.next()) {
					perms.put(res.getInt(1), res.getString(2));
				}
			} catch (SQLException e) {
				logger.log(Level.WARNING, "Problem getting permissions from db", e);
			}
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem forming statement to get permissions from db", e);
		}
		return perms;
	}

	public void registerPlayerType(Group g, PlayerType type) {
		try (Connection connection = db.getConnection();
				PreparedStatement addType = connection.prepareStatement(
						"insert into groupPlayerTypes (group_id, rank_id, type_name, parent_rank_id) values(?,?,?,?)");) {
			addType.setInt(1, g.getGroupId());
			addType.setInt(2, type.getId());
			addType.setString(3, type.getName());
			if (type.getParent() == null) {
				addType.setNull(4, Types.INTEGER);
			} else {
				addType.setInt(4, type.getParent().getId());
			}
			addType.execute();
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem adding player type " + type.getName() + " for " + g.getName(), e);
		}
	}

	public void removePlayerType(Group g, PlayerType type) {
		try (Connection connection = db.getConnection();
				PreparedStatement removeType = connection
						.prepareStatement("delete from groupPlayerTypes where group_id = ? and rank_id = ?");) {
			removeType.setInt(1, g.getGroupId());
			removeType.setInt(2, type.getId());
			removeType.execute();
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem removing player type " + type.getName() + " from " + g.getName(), e);
		}
	}

	public void updatePlayerTypeName(Group g, PlayerType type) {
		try (Connection connection = db.getConnection();
				PreparedStatement renameType = connection.prepareStatement(
						"update groupPlayerTypes set type_name = ? where group_id = ? and rank_id = ?");) {
			renameType.setString(1, type.getName());
			renameType.setInt(2, g.getGroupId());
			renameType.setInt(3, type.getId());
			renameType.execute();
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem updating player type name " + type.getName() + " from " + g.getName(),
					e);
		}
	}

	public void mergeGroup(Group groupThatStays, Group groupToMerge) {
		try (Connection connection = db.getConnection();
				PreparedStatement mergeGroup = connection.prepareStatement("call mergeintogroup(?,?)");) {
			mergeGroup.setInt(1, groupThatStays.getGroupId());
			mergeGroup.setInt(2, groupToMerge.getGroupId());
			mergeGroup.execute();
		} catch (SQLException e) {
			logger.log(Level.WARNING,
					"Problem merging group " + groupToMerge.getName() + " into " + groupThatStays.getName(), e);
		}
	}

	public void addGroupInvitation(UUID uuid, Group group, PlayerType role) {
		try (Connection connection = db.getConnection();
				PreparedStatement addGroupInvitation = connection.prepareStatement(
						"insert into group_invitation(uuid, group_id, rank_id) values(?, ?, ?) on duplicate key update rank_id=values(rank_id), date=now()")) {
			addGroupInvitation.setString(1, uuid.toString());
			addGroupInvitation.setInt(2, group.getGroupId());
			addGroupInvitation.setInt(3, role.getId());
			addGroupInvitation.executeUpdate();
		} catch (SQLException e) {
			logger.log(Level.WARNING,
					"Problem adding group " + group.getName() + " invite for " + uuid + " with role " + role, e);
		}
	}

	public void removeGroupInvitation(UUID uuid, Group group) {
		try (Connection connection = db.getConnection();
				PreparedStatement removeGroupInvitation = connection
						.prepareStatement("delete from group_invitation where uuid = ? and group_id = ?");) {
			removeGroupInvitation.setString(1, uuid.toString());
			removeGroupInvitation.setInt(2, group.getGroupId());
			removeGroupInvitation.executeUpdate();
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem removing group " + group.getName() + " invite for " + uuid, e);
		}
	}

	/**
	 * Use this method to load all invitations to all groups.
	 */
	public void loadGroupsInvitations() {
		try (Connection connection = db.getConnection();
				PreparedStatement loadGroupsInvitations = connection
						.prepareStatement("select uuid, group_id, rank_id from group_invitation");
				ResultSet set = loadGroupsInvitations.executeQuery();) {
			while (set.next()) {
				String uuid = set.getString(1);
				int groupId = set.getInt(2);
				int rankId = set.getInt(3);
				UUID playerUUID = UUID.fromString(uuid);
				Group g = GroupAPI.getGroupById(groupId);
				PlayerType type = null;
				if (g != null) {
					type = g.getPlayerTypeHandler().getType(rankId);
				}
				if (type != null) {
					g.addInvite(playerUUID, type, false);
					PlayerListener.addNotification(playerUUID, g);
				}
			}
		} catch (SQLException e) {
			logger.log(Level.WARNING, "Problem loading all group invitations", e);
		}
	}

	public void addLink(GroupLink link) {
		try (Connection insertConn = db.getConnection();
				PreparedStatement insertLink = insertConn.prepareStatement(
						"insert into nl_group_links (originating_group_id, originating_type_id, target_group_id, target_type_id) "
								+ "values(?,?, ?,?);",
						Statement.RETURN_GENERATED_KEYS)) {
			insertLink.setInt(1, link.getOriginatingGroup().getGroupId());
			insertLink.setInt(2, link.getOriginatingType().getId());
			insertLink.setInt(3, link.getTargetGroup().getGroupId());
			insertLink.setInt(4, link.getTargetType().getId());
			insertLink.execute();
			try (ResultSet rs = insertLink.getGeneratedKeys()) {
				if (!rs.next()) {
					throw new IllegalStateException("Inserting link did not generate an id");
				}
				link.setID(rs.getInt(1));
			}
		} catch (SQLException e) {
			logger.log(Level.SEVERE, "Failed to insert new link: ", e);
		}
	}

	public void removeLink(GroupLink link) {
		if (link.getID() == -1) {
			throw new IllegalStateException("Link id was not set");
		}
		try (Connection insertConn = db.getConnection();
				PreparedStatement removeLink = insertConn
						.prepareStatement("delete from nl_group_links where link_id = ?")) {
			removeLink.setInt(1, link.getID());
			removeLink.execute();
		} catch (SQLException e) {
			logger.log(Level.SEVERE, "Failed to remove link: ", e);
		}
	}

	public GroupManager loadAllGroups() {
		Map<Integer, Group> groupById = new HashMap<>();
		Map<String, Group> groupByName = new HashMap<>();
		Map<Integer, JsonObject> groupMetaById = new HashMap<>();
		JsonParser jsonParser = new JsonParser();
		try (Connection connection = db.getConnection();
				PreparedStatement getGroups = connection
						.prepareStatement("select group_name, group_id, meta_data from faction_id");
				ResultSet groups = getGroups.executeQuery()) {
			while (groups.next()) {
				String name = groups.getString(1);
				Integer id = groups.getInt(2);
				String rawMeta = groups.getString(3);
				Group group = new Group(name, id);
				groupById.put(id, group);
				groupByName.put(name.toLowerCase(), group);
				groupMetaById.put(id, (JsonObject) jsonParser.parse(rawMeta));
			}
		} catch (SQLException e) {
			logger.severe("Failed to load groups: " + e.toString());
			return null;
		}
		Map<Integer, Map<Integer, PlayerType>> retrievedTypes = new TreeMap<>();
		Map<Integer, Map<Integer, List<PlayerType>>> pendingParents = new TreeMap<>();
		// load all player types without linking them in any way initially
		try (Connection connection = db.getConnection();
				PreparedStatement getTypes = connection
						.prepareStatement("select type_name, group_id, rank_id, parent_rank_id from groupPlayerTypes");
				ResultSet types = getTypes.executeQuery()) {
			while (types.next()) {
				String name = types.getString(1);
				int groupId = types.getInt(2);
				int rankId = types.getInt(3);
				int parentId = types.getInt(4);
				PlayerType type = new PlayerType(name, rankId, null, new LinkedList<PermissionType>(), null);
				Map<Integer, PlayerType> typeMap = retrievedTypes.computeIfAbsent(groupId, i -> new TreeMap<>());
				typeMap.put(rankId, type);
				Map<Integer, List<PlayerType>> parentMap = pendingParents.computeIfAbsent(groupId,
						i -> new TreeMap<>());
				List<PlayerType> brothers = parentMap.computeIfAbsent(parentId, i -> new LinkedList<>());
				brothers.add(type);
			}
		} catch (SQLException e) {
			logger.severe("Failed to load player types: " + e.toString());
			return null;
		}
		// properly map player type children/parents
		for (Entry<Integer, Map<Integer, PlayerType>> entry : retrievedTypes.entrySet()) {
			Map<Integer, PlayerType> loadedTypes = entry.getValue();
			Map<Integer, List<PlayerType>> loadedParents = pendingParents.get(entry.getKey());
			Group group = groupById.get(entry.getKey());
			if (group == null) {
				logger.log(Level.WARNING, "Found player types, but no group for id " + entry.getKey());
				continue;
			}
			PlayerType root = loadedTypes.get(PlayerTypeHandler.OWNER_ID);
			PlayerTypeHandler handler = new PlayerTypeHandler(root, group);
			Queue<PlayerType> toHandle = new LinkedList<>();
			toHandle.add(root);
			while (!toHandle.isEmpty()) {
				PlayerType parent = toHandle.poll();
				List<PlayerType> children = loadedParents.get(parent.getId());
				loadedParents.remove(parent.getId());
				if (children == null) {
					continue;
				}
				for (PlayerType child : children) {
					// parent is intentionally non modifiable, so we create a new instance
					PlayerType type = new PlayerType(child.getName(), child.getId(), parent, group);
					parent.addChild(type);
					handler.loadPlayerType(type);
					toHandle.add(type);
					loadedTypes.remove(type.getId());
				}
			}
			if (!loadedTypes.isEmpty()) {
				// a type exists for this group, which is not part of the tree rooted at perm 0
				logger.log(Level.WARNING,
						"A total of " + loadedTypes.values().size() + " player types could not be loaded for group "
								+ group.getName() + ", because they arent part of the normal tree structure");
			}
			group.setPlayerTypeHandler(handler);
		}
		// load members
		try (Connection connection = db.getConnection();
				PreparedStatement getTypes = connection
						.prepareStatement("select group_id, member_name, rank_id from faction_member");
				ResultSet types = getTypes.executeQuery()) {
			while (types.next()) {
				int groupId = types.getInt(1);
				String memberUUIDString = types.getString(2);
				UUID member = null;
				if (memberUUIDString != null) {
					member = UUID.fromString(memberUUIDString);
				} else {
					logger.log(Level.WARNING, "Found invalid uuid " + memberUUIDString + " for group " + groupId);
					continue;
				}
				int rankId = types.getInt(3);
				Group group = groupById.get(groupId);
				if (group == null) {
					logger.log(Level.WARNING, "Couldnt not load group " + groupId + " from cache to add member");
					continue;
				}
				group.addToTracking(member, group.getPlayerTypeHandler().getType(rankId), false);
			}
		} catch (SQLException e) {
			logger.severe("Failed to load group members: " + e.toString());
		}
		// load permissions
		try (Connection connection = db.getConnection();
				PreparedStatement getTypes = connection
						.prepareStatement("select group_id,rank_id,perm_id from permissionByGroup");
				ResultSet types = getTypes.executeQuery()) {
			while (types.next()) {
				int groupId = types.getInt(1);
				int rankId = types.getInt(2);
				int permId = types.getInt(3);
				PermissionType perm = PermissionType.getPermission(permId);
				Group group = groupById.get(groupId);
				if (group == null) {
					logger.log(Level.WARNING, "Couldnt not load group " + groupId + " from cache to add permission");
					continue;
				}
				if (perm != null) {
					group.getPlayerTypeHandler().getType(rankId).addPermission(perm, false);
				}
			}
		} catch (SQLException e) {
			logger.severe("Failed to load group permissions: " + e.toString());
		}
		// load merged group remapping
		try (Connection connection = db.getConnection();
				PreparedStatement getTypes = connection.prepareStatement("select oldGroup, newGroup from mergedGroups");
				ResultSet types = getTypes.executeQuery()) {
			while (types.next()) {
				int oldId = types.getInt(1);
				int newId = types.getInt(2);
				Group group = groupById.get(newId);
				if (group == null) {
					logger.log(Level.WARNING, "Inconsistent mapping from " + oldId + " to " + newId + " found");
				} else {
					groupById.put(oldId, group);
				}
			}
		} catch (SQLException e) {
			logger.severe("Failed to merged group links " + e.toString());
		}
		// load group link
		try (Connection connection = db.getConnection();
				PreparedStatement getTypes = connection
						.prepareStatement("select link_id, originating_group_id, originating_type_id, "
								+ "target_group_id, target_type_id from nl_group_links");
				ResultSet links = getTypes.executeQuery()) {
			while (links.next()) {
				int linkId = links.getInt(1);
				int originatingGroupId = links.getInt(2);
				int originatingTypeId = links.getInt(3);
				int targetGroupId = links.getInt(4);
				int targetTypeId = links.getInt(5);
				Group originatingGroup = groupById.get(originatingGroupId);
				if (originatingGroup == null) {
					logger.log(Level.SEVERE, "Link loaded had no group: " + linkId);
					continue;
				}
				Group targetGroup = groupById.get(targetGroupId);
				if (targetGroup == null) {
					logger.log(Level.SEVERE, "Link loaded had no group: " + linkId);
					continue;
				}
				PlayerType originatingPlayerType = originatingGroup.getPlayerTypeHandler().getType(originatingTypeId);
				if (originatingPlayerType == null) {
					logger.log(Level.SEVERE, "Link loaded had no og type: " + linkId);
					continue;
				}
				PlayerType targetPlayerType = targetGroup.getPlayerTypeHandler().getType(targetTypeId);
				if (targetPlayerType == null) {
					logger.log(Level.SEVERE, "Link loaded had no target type: " + linkId);
					continue;
				}
				GroupLink link = new GroupLink(originatingGroup, originatingPlayerType, targetGroup, targetPlayerType);
				link.setID(linkId);
				originatingGroup.addOutgoingLink(link);
				targetGroup.addIncomingLink(link);
			}
		} catch (SQLException e) {
			logger.severe("Failed to load group links " + e.toString());
		}
		logger.log(Level.INFO, "Loaded a total of " + groupByName.size() + " groups with " + groupById.size()
				+ " ids from the database");
		GroupMetaDataAPI.offerRawMeta(groupMetaById);
		return new GroupManager(this, groupByName, groupById);
	}

	public boolean updateDatabase() {
		return db.updateDatabase();
	}
}
