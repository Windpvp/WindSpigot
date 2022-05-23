package net.minecraft.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import ga.windpvp.windspigot.async.AsyncUtil;
import ga.windpvp.windspigot.async.ResettableLatch;
import ga.windpvp.windspigot.config.WindSpigotConfig;
import javafixes.concurrency.ReusableCountLatch;
import me.elier.nachospigot.config.NachoConfig;
import me.rastrian.dev.utils.IndexedLinkedHashSet;
import net.openhft.affinity.AffinityLock;
import net.openhft.affinity.AffinityStrategies;

public class EntityTracker {

	public IndexedLinkedHashSet<EntityTrackerEntry> c = new IndexedLinkedHashSet<>();

	public IndexedLinkedHashSet<EntityTrackerEntry> getTrackedEntities() {
		return c;
	}

	public IntHashMap<EntityTrackerEntry> trackedEntities = new IntHashMap<>();

	public IntHashMap<EntityTrackerEntry> getTrackedEntityHashTable() {
		return trackedEntities;
	}

	private int noTrackDistance = 0;

	public int getNoTrackDistance() {
		return this.noTrackDistance;
	}

	public void setNoTrackDistance(int noTrackDistance) {
		this.noTrackDistance = noTrackDistance;
	}

	private int e; 
	
	// All threads but one are handling a task, main thread runs a task once these tasks are started
	private static ExecutorService trackingThreadPool = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("WindSpigot Entity Tracker Thread").build());

	public EntityTracker(WorldServer worldserver) {
		this.e = 128;
	}

	public void track(Entity entity) {
		if (entity instanceof EntityPlayer) {
			this.addEntity(entity, 512, 2);
			/*
			 * EntityPlayer entityplayer = (EntityPlayer) entity; for (EntityTrackerEntry
			 * entitytrackerentry : this.c) { if (entitytrackerentry.tracker !=
			 * entityplayer) { entitytrackerentry.updatePlayer(entityplayer); } }
			 */
		} else if (entity instanceof EntityFishingHook) {
			this.addEntity(entity, 64, 5, true);
		} else if (entity instanceof EntityArrow) {
			this.addEntity(entity, 64, 20, false);
		} else if (entity instanceof EntitySmallFireball) {
			this.addEntity(entity, 64, 10, false);
		} else if (entity instanceof EntityFireball) {
			this.addEntity(entity, 64, 10, false);
		} else if (entity instanceof EntitySnowball) {
			this.addEntity(entity, 64, 10, true);
		} else if (entity instanceof EntityEnderPearl) {
			this.addEntity(entity, 64, 10, true);
		} else if (entity instanceof EntityEnderSignal) {
			this.addEntity(entity, 64, 4, true);
		} else if (entity instanceof EntityEgg) {
			this.addEntity(entity, 64, 10, true);
		} else if (entity instanceof EntityPotion) {
			this.addEntity(entity, 64, 10, true);
		} else if (entity instanceof EntityThrownExpBottle) {
			this.addEntity(entity, 64, 10, true);
		} else if (entity instanceof EntityFireworks) {
			this.addEntity(entity, 64, 10, true);
		} else if (entity instanceof EntityItem) {
			this.addEntity(entity, 64, 20, true);
		} else if (entity instanceof EntityMinecartAbstract) {
			this.addEntity(entity, 80, 3, true);
		} else if (entity instanceof EntityBoat) {
			this.addEntity(entity, 80, 3, true);
		} else if (entity instanceof EntitySquid) {
			this.addEntity(entity, 64, 3, true);
		} else if (entity instanceof EntityWither) {
			this.addEntity(entity, 80, 3, false);
		} else if (entity instanceof EntityBat) {
			this.addEntity(entity, 80, 3, false);
		} else if (entity instanceof EntityEnderDragon) {
			this.addEntity(entity, 160, 3, true);
		} else if (entity instanceof IAnimal) {
			this.addEntity(entity, 80, 3, true);
		} else if (entity instanceof EntityTNTPrimed) {
			this.addEntity(entity, 160, 10, true);
		} else if (entity instanceof EntityFallingBlock) {
			this.addEntity(entity, 160, 20, true);
		} else if (entity instanceof EntityHanging) {
			this.addEntity(entity, 160, Integer.MAX_VALUE, false);
		} else if (entity instanceof EntityArmorStand) {
			this.addEntity(entity, 160, 3, true);
		} else if (entity instanceof EntityExperienceOrb) {
			this.addEntity(entity, 160, 20, true);
		} else if (entity instanceof EntityEnderCrystal) {
			this.addEntity(entity, 256, Integer.MAX_VALUE, false);
		}

	}

	public void addEntity(Entity entity, int i, int j) {
		this.addEntity(entity, i, j, false);
	}

	public void addEntity(Entity entity, int i, final int j, boolean flag) {
		org.spigotmc.AsyncCatcher.catchOp("entity track"); // Spigot
		i = org.spigotmc.TrackingRange.getEntityTrackingRange(entity, i); // Spigot
		if (i > this.e) {
			i = this.e;
		}

		try {
			if (this.trackedEntities.b(entity.getId())) {
				untrackEntity(entity);
			}

			EntityTrackerEntry entitytrackerentry = createTracker(entity, i, j, flag); // IonSpigot

			this.c.add(entitytrackerentry);
			this.trackedEntities.a(entity.getId(), entitytrackerentry);
			// entitytrackerentry.scanPlayers(this.world.players);
			entitytrackerentry.addNearPlayers();
		} catch (Throwable throwable) {
			throwable.printStackTrace();
		}
	}

	// IonSpigot start
	private EntityTrackerEntry createTracker(Entity entity, int i, int j, boolean flag) {
		if (entity.isCannoningEntity && WindSpigotConfig.useFasterCannonTracker) {
			return new me.suicidalkids.ion.visuals.CannonTrackerEntry(this, entity, i, j, flag);
		} else {
			return new EntityTrackerEntry(this, entity, i, j, flag);
		}
	}
	// IonSpigot end

	public void untrackEntity(Entity entity) {
		org.spigotmc.AsyncCatcher.catchOp("entity untrack"); // Spigot
		if (entity instanceof EntityPlayer) {
			EntityPlayer entityplayer = (EntityPlayer) entity;
			for (EntityTrackerEntry entitytrackerentry : this.c) {
				entitytrackerentry.a(entityplayer);
			}
		}
		EntityTrackerEntry entry = this.trackedEntities.d(entity.getId());
		if (entry != null) {
			this.c.remove(entry);
			entry.a();
		}
	}

	public void updatePlayers() {
		// WindSpigot start- async entity tracker based of off this:
		// https://github.com/Argarian-Network/NachoSpigot/tree/async-entity-tracker
		Runnable trackerUpdateTask = () -> {	

			for (EntityTrackerEntry entry : c) {
				entry.update();
			}

		};

		// Handle all tasks but one on the tracking pool
		if (!WindSpigotConfig.disableTracking) {
			AsyncUtil.run(trackerUpdateTask, trackingThreadPool);
		} else {
			trackerUpdateTask.run();
		}
		// WindSpigot end
	}

	public void a(EntityPlayer entityplayer) {
		for (EntityTrackerEntry entitytrackerentry : this.c) {
			if (entitytrackerentry.getTracker() == entityplayer) {
				// entitytrackerentry.scanPlayers(this.world.players);
				entitytrackerentry.addNearPlayers();
			} else {
				entitytrackerentry.updatePlayer(entityplayer);
			}
		}
	}

	public void a(Entity entity, Packet<?> packet) {
		EntityTrackerEntry entitytrackerentry = this.trackedEntities.get(entity.getId());
		if (entitytrackerentry != null) {
			entitytrackerentry.broadcast(packet);
		}
	}

	public void sendPacketToEntity(Entity entity, Packet<?> packet) {
		EntityTrackerEntry entitytrackerentry = this.trackedEntities.get(entity.getId());
		if (entitytrackerentry != null) {
			entitytrackerentry.broadcastIncludingSelf(packet);
		}
	}

	public void untrackPlayer(EntityPlayer entityplayer) {
		for (EntityTrackerEntry entitytrackerentry : this.c) {
			entitytrackerentry.clear(entityplayer);
		}
	}

	/*
	 * public void a(EntityPlayer entityplayer, Chunk chunk) { for
	 * (EntityTrackerEntry entry : this.getTrackedEntities()) { if
	 * (entry.getTracker() != entityplayer && entry.getTracker().getChunkX() ==
	 * chunk.locX && entry.getTracker().getChunkZ() == chunk.locZ )
	 * entry.updatePlayer(entityplayer); } }
	 */
}