package wolfshotz.dml.entities;

import com.google.common.collect.Lists;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SoundType;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.attributes.AttributeModifierMap;
import net.minecraft.entity.ai.controller.BodyController;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SaddleItem;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.pathfinding.FlyingPathNavigator;
import net.minecraft.pathfinding.GroundPathNavigator;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistries;
import wolfshotz.dml.DMLRegistry;
import wolfshotz.dml.DragonMountsLegacy;
import wolfshotz.dml.client.anim.DragonAnimator;
import wolfshotz.dml.entities.ai.DragonBodyController;
import wolfshotz.dml.entities.ai.DragonMoveController;
import wolfshotz.dml.entities.ai.LifeStageController;
import wolfshotz.dml.entities.ai.goals.DragonBabuFollowParent;
import wolfshotz.dml.entities.ai.goals.DragonBreedGoal;
import wolfshotz.dml.entities.ai.goals.DragonLandGoal;
import wolfshotz.dml.misc.DragonEggBlock;
import wolfshotz.dml.util.MathX;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static net.minecraft.entity.ai.attributes.Attributes.*;

/**
 * Here be dragons.
 * <p>
 * Recreated: 10:50PM, 4/3/2020
 * Let the legacy live on
 *
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 * @author WolfShotz
 */
public abstract class TameableDragonEntity extends TameableEntity
{
    // base attributes
    public static final double BASE_SPEED_GROUND = 0.3;
    public static final double BASE_SPEED_FLYING = 0.6;
    public static final double BASE_DAMAGE = 18;
    public static final double BASE_HEALTH = 940;
    public static final double BASE_FOLLOW_RANGE = 16;
    public static final double BASE_FOLLOW_RANGE_FLYING = BASE_FOLLOW_RANGE * 2;
    public static final double ALTITUDE_FLYING_THRESHOLD = 2;
    public static final int REPRO_LIMIT = 2;
    public static final int BASE_KB_RESISTANCE = 1;
    public static final float BASE_WIDTH = 2.75f; // adult sizes
    public static final float BASE_HEIGHT = 2.75f;
    // data value IDs
    private static final DataParameter<Boolean> DATA_FLYING = EntityDataManager.createKey(TameableDragonEntity.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> DATA_SADDLED = EntityDataManager.createKey(TameableDragonEntity.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> DATA_BREATHING = EntityDataManager.createKey(TameableDragonEntity.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Integer> DATA_TICKS_ALIVE = EntityDataManager.createKey(TameableDragonEntity.class, DataSerializers.VARINT);

    // data NBT IDs
    private static final String NBT_SADDLED = "Saddle";
    private static final String NBT_TICKS_ALIVE = "TicksAlive";
    private static final String NBT_REPRO_COUNT = "ReproCount";

    // server/client delegates
    public LifeStageController lifeStageController;
    public DragonAnimator animator;
    public final List<DamageSource> damageImmunities = Lists.newArrayList();
    public int reproCount;

    public TameableDragonEntity(EntityType<? extends TameableDragonEntity> type, World world)
    {
        super(type, world);

        // enables walking over blocks
        stepHeight = 1;
        ignoreFrustumCheck = true;
        moveController = new DragonMoveController(this);
        if (isClient()) animator = new DragonAnimator(this);
    }

    @Override
    protected BodyController createBodyController()
    {
        return new DragonBodyController(this);
    }

    public static AttributeModifierMap.MutableAttribute getAttributes()
    {
        return MobEntity.func_233666_p_()
                .createMutableAttribute(MOVEMENT_SPEED, BASE_SPEED_GROUND)
                .createMutableAttribute(MAX_HEALTH, BASE_HEALTH)
                .createMutableAttribute(ATTACK_DAMAGE, BASE_FOLLOW_RANGE)
                .createMutableAttribute(KNOCKBACK_RESISTANCE, BASE_KB_RESISTANCE)
                .createMutableAttribute(ATTACK_DAMAGE, BASE_DAMAGE)
                .createMutableAttribute(FLYING_SPEED, BASE_SPEED_FLYING);
    }


    @Override
    protected void registerGoals() // TODO: Much Smarter AI and features
    {
        goalSelector.addGoal(1, new DragonLandGoal(this));
        goalSelector.addGoal(2, new SitGoal(this));
        goalSelector.addGoal(3, new MeleeAttackGoal(this, 1, true));
        goalSelector.addGoal(4, new DragonBabuFollowParent(this, 10));
        goalSelector.addGoal(5, new FollowOwnerGoal(this, 1.1d, 10f, 3.5f, true));
        goalSelector.addGoal(5, new DragonBreedGoal(this));
        goalSelector.addGoal(6, new WaterAvoidingRandomWalkingGoal(this, 1));
        goalSelector.addGoal(7, new LookAtGoal(this, LivingEntity.class, 16f));
        goalSelector.addGoal(8, new LookRandomlyGoal(this));

        targetSelector.addGoal(0, new OwnerHurtByTargetGoal(this));
        targetSelector.addGoal(1, new OwnerHurtTargetGoal(this));
        targetSelector.addGoal(2, new HurtByTargetGoal(this));
        targetSelector.addGoal(3, new NonTamedTargetGoal<>(this, AnimalEntity.class, false, e -> !(e instanceof TameableDragonEntity)));
    }

    @Override
    protected void registerData()
    {
        super.registerData();

        dataManager.register(DATA_FLYING, false);
        dataManager.register(DATA_SADDLED, false);
//        dataManager.register(DATA_BREATHING, false);
        dataManager.register(DATA_TICKS_ALIVE, LifeStageController.EnumLifeStage.ADULT.startTicks()); // default to adult stage
    }

    @Override
    public void writeAdditional(CompoundNBT compound)
    {
        super.writeAdditional(compound);
        compound.putBoolean(NBT_SADDLED, isSaddled());
        compound.putInt(NBT_TICKS_ALIVE, getTicksAlive());
        compound.putInt(NBT_REPRO_COUNT, reproCount);
    }

    @Override
    public void readAdditional(CompoundNBT compound)
    {
        super.readAdditional(compound);
        setSaddled(compound.getBoolean(NBT_SADDLED));
        if (compound.contains(NBT_TICKS_ALIVE)) setTicksAlive(compound.getInt(NBT_TICKS_ALIVE));
        this.reproCount = compound.getInt(NBT_REPRO_COUNT);
    }

    /**
     * Returns true if the dragon is saddled.
     */
    public boolean isSaddled() { return dataManager.get(DATA_SADDLED); }

    /**
     * Set or remove the saddle of the dragon.
     */
    public void setSaddled(boolean saddled) { dataManager.set(DATA_SADDLED, saddled); }

    public int getTicksAlive() { return dataManager.get(DATA_TICKS_ALIVE); }

    public void setTicksAlive(int ticksAlive)
    {
        dataManager.set(DATA_TICKS_ALIVE, ticksAlive);
        getLifeStageController().setTicksAlive(ticksAlive);
    }

    public void addReproCount() { reproCount++; }

    public boolean canFly()
    {
        // hatchling's can't fly
        return !isHatchling();
    }

    public boolean shouldFly() { return canFly() && !isInWater() && getAltitude() > ALTITUDE_FLYING_THRESHOLD; }

    /**
     * Returns true if the entity is flying.
     */
    public boolean isFlying() { return dataManager.get(DATA_FLYING); }

    /**
     * Set the flying flag of the entity.
     */
    public void setFlying(boolean flying) { dataManager.set(DATA_FLYING, flying); }

    public boolean isBreathing() { return dataManager.get(DATA_BREATHING); }

    public void setBreathing(boolean breathing) { dataManager.set(DATA_BREATHING, breathing); }

    public LifeStageController getLifeStageController()
    {
        if (lifeStageController == null) lifeStageController = new LifeStageController(this);
        return lifeStageController;
    }

    @Override
    public void livingTick()
    {
        getLifeStageController().tick();

        if (isServer())
        {
            // update flying state based on the distance to the ground
            boolean flying = shouldFly();
            if (flying != isFlying())
            {
                // notify client
                setFlying(flying);

                // update AI follow range (needs to be updated before creating
                // new PathNavigate!)
                getAttribute(FOLLOW_RANGE).setBaseValue(flying? BASE_FOLLOW_RANGE_FLYING : BASE_FOLLOW_RANGE);

                // update pathfinding method
                if (flying) navigator = new FlyingPathNavigator(this, world);
                else navigator = new GroundPathNavigator(this, world);
            }

//            // update breath state
//            if (isBreathing())
//            {
//                if (getControllingPassenger() == null) setBreathing(false);
//                else tickBreathWeapon();
//            }
        }
        else
        {
            // update animations on the client
            animator.tick();
            updateArmSwingProgress();
        }

        super.livingTick();
    }

    @Override
    public void travel(Vector3d vec3d)
    {
        if (!isFlying()) super.travel(vec3d);

        if (world.isRemote) return;
        PlayerEntity rider = getRidingPlayer();
        if (rider == null || !isOwner(rider)) return;

        rotationYawHead = rider.rotationYawHead;
        rotationPitch = rider.rotationPitch / 2;

        if (world.isRemote) return;


        // lift off with a jump
        if (!isFlying() && rider.isJumping) liftOff();

        double x = getPosX();
        double y = getPosY();
        double z = getPosZ();

        // control direction with movement keys
        if (rider.moveStrafing != 0 || rider.moveForward != 0)
        {
            Vector3d wp = rider.getLookVec();

            if (rider.moveForward < 0) wp = wp.rotateYaw(MathX.PI_F);
            else if (rider.moveStrafing > 0) wp = wp.rotateYaw(MathX.PI_F * 0.5f);
            else if (rider.moveStrafing < 0) wp = wp.rotateYaw(MathX.PI_F * -0.5f);

            x += wp.x * 10;
            y += wp.y * 10;
            z += wp.z * 10;

        }

        getMoveHelper().setMoveTo(x, y, z, 1);
    }

    /**
     * Returns the distance to the ground while the entity is flying.
     */
    public double getAltitude()
    {
        BlockPos.Mutable pos = getPosition().toMutable();
        while (pos.getY() > 0 && !world.getBlockState(pos).getMaterial().isSolid()) pos.move(0, -1, 0);

        return getPosY() - pos.getY();
    }

    /**
     * Causes this entity to lift off if it can fly.
     */
    public void liftOff() { if (canFly()) jump(); }

    @Override
    protected float getJumpUpwardsMotion()
    {
        // stronger jumps for easier lift-offs
        return canFly()? 1 : super.getJumpUpwardsMotion();
    }

    @Override
    public boolean onLivingFall(float distance, float damageMultiplier)
    {
        if (canFly()) return false;
        return super.onLivingFall(distance, damageMultiplier);
    }

    /**
     * Handles entity death timer, experience orb and particle creation
     */
    @Override
    protected void onDeathUpdate()
    {
        // unmount any riding entities
        removePassengers();

        // freeze at place
        setMotion(Vector3d.ZERO);
        rotationYaw = prevRotationYaw;
        rotationYawHead = prevRotationYawHead;

        if (deathTime >= getMaxDeathTime()) remove(); // actually delete entity after the time is up

        deathTime++;
    }

    // Ok so some basic notes here:
    // if the action result is a SUCCESS, the player swings its arm.
    // however, itll send that arm swing twice if we aren't careful.
    // essentially, returning SUCCESS on server will send a swing arm packet to notify the client to animate the arm swing
    // client tho, it will just animate it.
    // so if we aren't careful, both will happen. So its important to do the following for common execution:
    // ActionResultType.func_233537_a_(World::isRemote)
    // essentially, if the provided boolean is true, it will return SUCCESS, else CONSUME.
    // so since the world is client, it will be SUCCESS on client and CONSUME on server.
    // That way, the server never sends the arm swing packet.
    @Override
    public ActionResultType func_230254_b_(PlayerEntity player, Hand hand)
    {
        ItemStack stack = player.getHeldItem(hand);

        ActionResultType stackResult = stack.interactWithEntity(player, this, hand);
        if (stackResult.isSuccessOrConsume()) return stackResult;

        final ActionResultType SUCCESS = ActionResultType.func_233537_a_(isClient());

        // heal
        if (getHealthRelative() < 1 && isFoodItem(stack))
        {
            heal(stack.getItem().getFood().getHealing());
            playSound(getEatSound(stack), 0.7f, 1);
            stack.shrink(1);
            return SUCCESS;
        }

        // saddle up!
        if (isTamedFor(player) && !isChild() && !isSaddled() && stack.getItem() instanceof SaddleItem)
        {
            stack.shrink(1);
            setSaddled(true);
            world.playSound(null, getPosX(), getPosY(), getPosZ(), SoundEvents.ENTITY_HORSE_SADDLE, getSoundCategory(), 1, 1);
            return SUCCESS;
        }

        // tame
        if (isBreedingItem(stack) && !isTamed())
        {
            stack.shrink(1);
            if (isServer()) tamedFor(player, getRNG().nextInt(5) == 0);
            return SUCCESS;
        }

        // sit!
        if (isTamedFor(player) && player.isSneaking())
        {
            if (isServer())
            {
                navigator.clearPath();
                if (func_233685_eM_()) setAttackTarget(null);
                func_233687_w_(!func_233685_eM_());
            }
            return SUCCESS;
        }

        // ride on
        if (isTamed() && isSaddled() && !isChild() && !isBreedingItem(stack))
        {
            if (isServer())
            {
                setRidingPlayer(player);
                navigator.clearPath();
                setAttackTarget(null);
            }
            func_233686_v_(false);
            return SUCCESS;
        }

        return super.func_230254_b_(player, hand);
    }

    /**
     * Returns the sound this mob makes while it's alive.
     */
    @Override
    protected SoundEvent getAmbientSound()
    {
        if (getRNG().nextInt(5) == 0) return SoundEvents.ENTITY_ENDER_DRAGON_GROWL;
        return DMLRegistry.DRAGON_BREATHE_SOUND.get();
    }

    @Nullable
    @Override
    protected SoundEvent getHurtSound(DamageSource damageSourceIn) { return SoundEvents.ENTITY_ENDER_DRAGON_HURT; }

    public SoundEvent getStepSound() { return DMLRegistry.DRAGON_STEP_SOUND.get(); }

    /**
     * Returns the sound this mob makes on death.
     */
    @Override
    protected SoundEvent getDeathSound() { return DMLRegistry.DRAGON_DEATH_SOUND.get(); }

    @Override
    public SoundEvent getEatSound(ItemStack itemStackIn) { return SoundEvents.ENTITY_GENERIC_EAT; }

    public SoundEvent getAttackSound() { return SoundEvents.ENTITY_GENERIC_EAT; }

    public SoundEvent getWingsSound() { return SoundEvents.ENTITY_ENDER_DRAGON_FLAP; }

    /**
     * Plays step sound at given x, y, z for the entity
     */
    @Override
    protected void playStepSound(BlockPos entityPos, BlockState state)
    {
        if (isInWater()) return;

        // override sound type if the top block is snowy
        SoundType soundType = state.getSoundType();
        if (world.getBlockState(entityPos.up()).getBlock() == Blocks.SNOW)
            soundType = Blocks.SNOW.getSoundType(state, world, entityPos, this);

        // play stomping for bigger dragons
        SoundEvent stepSound = getStepSound();
        if (isHatchling()) stepSound = soundType.getStepSound();

        playSound(stepSound, soundType.getVolume(), -1f);
    }

    /**
     * Get number of ticks, at least during which the living entity will be silent.
     */
    @Override
    public int getTalkInterval() { return 240; }

    @Override
    protected float getSoundVolume() { return getScale(); }

    @Override
    protected float getSoundPitch() { return getScale() - 2; }

    public float getSoundPitch(SoundEvent sound) { return getSoundPitch(); }

    @Override
    public void playSound(SoundEvent soundIn, float volume, float pitch) { playSound(soundIn, volume, pitch, false); }

    public void playSound(SoundEvent sound, float volume, float pitch, boolean local)
    {
        if (isSilent()) return;

        volume *= getSoundVolume();
        pitch *= getSoundPitch(sound);

        if (local) world.playSound(getPosX(), getPosY(), getPosZ(), sound, getSoundCategory(), volume, pitch, false);
        else world.playSound(null, getPosX(), getPosY(), getPosZ(), sound, getSoundCategory(), volume, pitch);
    }

    @Override
    public ItemStack getPickedResult(RayTraceResult target)
    {
        return new ItemStack(ForgeRegistries.ITEMS.getValue(DragonMountsLegacy.rl(getType().getRegistryName().getPath() + "_spawn_egg")));
    }

    /**
     * Called when a player interacts with a mob. e.g. gets milk from a cow, gets into the saddle on a pig.
     */
    public boolean isFoodItem(ItemStack stack)
    {
        return stack.getItem().isFood() && stack.getItem().getFood().isMeat();
    }

    @Override
    public boolean isBreedingItem(ItemStack stack)
    {
        return ItemTags.FISHES.contains(stack.getItem());
    }

    public void tamedFor(PlayerEntity player, boolean successful)
    {
        if (successful)
        {
            setTamed(true);
            navigator.clearPath();
            setAttackTarget(null);
            setOwnerId(player.getUniqueID());
            if (world.isRemote) playTameEffect(true);
            world.setEntityState(this, (byte) 7);
        }
        else
        {
            if (world.isRemote) playTameEffect(false);
            world.setEntityState(this, (byte) 6);
        }
    }

    public boolean isTamedFor(PlayerEntity player) { return isTamed() && isOwner(player); }

    public void addImmunities(DamageSource... sources) { damageImmunities.addAll(Arrays.asList(sources)); }

    /**
     * Returns the height of the eyes. Used for looking at other entities.
     */
    @Override
    protected float getStandingEyeHeight(Pose poseIn, EntitySize sizeIn)
    {
        float eyeHeight = super.getStandingEyeHeight(poseIn, sizeIn);

        if (func_233685_eM_()) eyeHeight *= 0.8f;

        return eyeHeight;
    }

    /**
     * Returns the Y offset from the entity's position for any entity riding this one.
     */
    @Override
    public double getMountedYOffset()
    {
        return (func_233685_eM_()? 1.7f : 2f) * getScale();
    }

    /**
     * Returns render size modifier
     */
    @Override
    public float getRenderScale()
    {
        return getScale();
    }

    /**
     * Determines if an entity can be despawned, used on idle far away entities
     */
    @Override
    public boolean canDespawn(double distanceToClosestPlayer) { return false; }

    /**
     * returns true if this entity is by a ladder, false otherwise
     */
    @Override
    public boolean isOnLadder()
    {
        // this better doesn't happen...
        return false;
    }

    @Override
    protected void dropSpecialItems(DamageSource source, int looting, boolean recentlyHitIn)
    {
        super.dropSpecialItems(source, looting, recentlyHitIn);

        if (isSaddled()) entityDropItem(Items.SADDLE);
    }

    public boolean attackEntityAsMob(Entity entityIn)
    {
        boolean attacked = entityIn.attackEntityFrom(DamageSource.causeMobDamage(this), (float) getAttribute(ATTACK_DAMAGE).getValue());

        if (attacked) applyEnchantments(this, entityIn);

        return attacked;
    }

    public void onWingsDown(float speed)
    {
        if (!isInWater())
        {
            // play wing sounds
            float pitch = (1 - speed);
            float volume = 0.3f + (1 - speed) * 0.2f;
            playSound(getWingsSound(), volume, pitch, true);
        }
    }

    @Override
    public void swingArm(Hand hand)
    {
        // play eating sound
        playSound(getAttackSound(), 1, 0.7f);
        super.swingArm(hand);
    }

    /**
     * Called when the entity is attacked.
     */
    @Override
    public boolean attackEntityFrom(DamageSource src, float par2)
    {
        if (isInvulnerableTo(src)) return false;

        // don't just sit there!
        func_233687_w_(false);

        return super.attackEntityFrom(src, par2);
    }

    /**
     * Returns true if the mob is currently able to mate with the specified mob.
     */
    @Override
    public boolean canMateWith(AnimalEntity mate)
    {
        if (mate == this) return false; // No. Just... no.
        else if (!(mate instanceof TameableDragonEntity)) return false;
        else if (!canReproduce()) return false;

        TameableDragonEntity dragonMate = (TameableDragonEntity) mate;

        if (!dragonMate.isTamed()) return false;
        else if (!dragonMate.canReproduce()) return false;
        else return isInLove() && dragonMate.isInLove();
    }

    public boolean canReproduce() { return isTamed() && reproCount < REPRO_LIMIT; }

    @Override
    public void func_234177_a_(ServerWorld world, AnimalEntity mate)
    {
        if (!(mate instanceof TameableDragonEntity)) throw new IllegalArgumentException("The mate isn't a dragon");

        // pick a breed to inherit from
        DragonEggEntity egg = DMLRegistry.EGG_ENTITY.get().create(world);
        if (getRNG().nextBoolean()) egg.setEggType(DragonEggBlock.lookUp(getType()));
        else egg.setEggType(DragonEggBlock.lookUp(mate.getType()));

        // mix the custom names in case both parents have one
        if (hasCustomName() && mate.hasCustomName())
        {
            String p1Name = getCustomName().getString();
            String p2Name = mate.getCustomName().getString();
            String babyName;

            if (p1Name.contains(" ") || p2Name.contains(" "))
            {
                // combine two words with space
                // "Tempor Invidunt Dolore" + "Magna"
                // = "Tempor Magna" or "Magna Tempor"
                String[] p1Names = p1Name.split(" ");
                String[] p2Names = p2Name.split(" ");

                p1Name = DragonBreedGoal.fixChildName(p1Names[rand.nextInt(p1Names.length)]);
                p2Name = DragonBreedGoal.fixChildName(p2Names[rand.nextInt(p2Names.length)]);

                babyName = rand.nextBoolean()? p1Name + " " + p2Name : p2Name + " " + p1Name;
            }
            else
            {
                // scramble two words
                // "Eirmod" + "Voluptua"
                // = "Eirvolu" or "Volueir" or "Modptua" or "Ptuamod" or ...
                if (rand.nextBoolean()) p1Name = p1Name.substring(0, (p1Name.length() - 1) / 2);
                else p1Name = p1Name.substring((p1Name.length() - 1) / 2);

                if (rand.nextBoolean()) p2Name = p2Name.substring(0, (p2Name.length() - 1) / 2);
                else p2Name = p2Name.substring((p2Name.length() - 1) / 2);

                p2Name = DragonBreedGoal.fixChildName(p2Name);

                babyName = rand.nextBoolean()? p1Name + p2Name : p2Name + p1Name;
            }

            egg.setCustomName(new StringTextComponent(babyName));
        }

        // increase reproduction counter
        addReproCount();
        ((TameableDragonEntity) mate).addReproCount();
        egg.setPosition(getPosX(), getPosY(), getPosZ());
        world.addEntity(egg);
    }

    @Nullable
    @Override
    public AgeableEntity func_241840_a(ServerWorld p_241840_1_, AgeableEntity p_241840_2_) { return null; }

    @Override
    public boolean shouldAttackEntity(LivingEntity target, LivingEntity owner)
    {
        if (target instanceof TameableEntity) return !Objects.equals(((TameableEntity) target).getOwner(), owner);
        return true;
    }

    @Override
    public boolean canAttack(EntityType<?> typeIn) { return !isHatchling() && super.canAttack(typeIn); }

    @Override
    public boolean canAttack(LivingEntity target) { return !isHatchling() && super.canAttack(target); }

    /**
     * For vehicles, the first passenger is generally considered the controller and "drives" the vehicle. For example,
     * Pigs, Horses, and Boats are generally "steered" by the controlling passenger.
     */
    @Override
    public Entity getControllingPassenger()
    {
        List<Entity> list = getPassengers();
        return list.isEmpty()? null : list.get(0);
    }

    @Override
    public boolean canPassengerSteer()
    {
        // must always return false or the vanilla movement code interferes
        // with DragonMoveHelper
        return false;
    }

    public PlayerEntity getRidingPlayer()
    {
        Entity entity = getControllingPassenger();
        if (entity instanceof PlayerEntity) return (PlayerEntity) entity;
        else return null;
    }

    public void setRidingPlayer(PlayerEntity player)
    {
        player.rotationYaw = rotationYaw;
        player.rotationPitch = rotationPitch;
        player.startRiding(this);
    }

    @Override
    public void updatePassenger(Entity passenger)
    {
        Entity riddenByEntity = getControllingPassenger();
        if (riddenByEntity != null)
        {
            Vector3d pos = new Vector3d(0, getMountedYOffset() + riddenByEntity.getYOffset(), 0.8 * getScale())
                    .rotateYaw((float) Math.toRadians(-renderYawOffset))
                    .add(getPositionVec());
            passenger.setPosition(pos.x, pos.y, pos.z);

            // fix rider rotation
            if (getRidingEntity() instanceof LivingEntity)
            {
                LivingEntity rider = ((LivingEntity) riddenByEntity);
                rider.prevRotationPitch = rider.rotationPitch;
                rider.prevRotationYaw = rider.rotationYaw;
                rider.renderYawOffset = renderYawOffset;
            }
        }
    }

    public boolean isInvulnerableTo(DamageSource src)
    {
        Entity srcEnt = src.getTrueSource();
        if (srcEnt != null)
        {
            // ignore own damage
            if (srcEnt == this) return true;

            // ignore damage from riders
            if (isPassenger(srcEnt)) return true;
        }

        return damageImmunities.contains(src);
    }

    /**
     * Returns the entity's health relative to the maximum health.
     *
     * @return health normalized between 0 and 1
     */
    public double getHealthRelative() { return getHealth() / (double) getMaxHealth(); }

    public int getDeathTime() { return deathTime; }

    public int getMaxDeathTime() { return 120; }

    /**
     * Public wrapper for protected final setScale(), used by DragonLifeStageHelper.
     */
    @Override
    public void recalculateSize()
    {
        double posXTmp = getPosX();
        double posYTmp = getPosY();
        double posZTmp = getPosZ();
        boolean onGroundTmp = onGround;

        super.recalculateSize();

        // workaround for a vanilla bug; the position is apparently not set correcty
        // after changing the entity size, causing asynchronous server/client positioning
        setPosition(posXTmp, posYTmp, posZTmp);

        // otherwise, setScale stops the dragon from landing while it is growing
        onGround = onGroundTmp;
    }

    /**
     * The age value may be negative or positive or zero. If it's negative, it get's incremented on each tick, if it's
     * positive, it get's decremented each tick. Don't confuse this with EntityLiving.getAge. With a negative value the
     * Entity is considered a child.
     */
    @Override
    public int getGrowingAge()
    {
        // adapter for vanilla code to enable breeding interaction
        return isAdult()? 0 : -1;
    }

    /**
     * The age value may be negative or positive or zero. If it's negative, it get's incremented on each tick, if it's
     * positive, it get's decremented each tick. With a negative value the Entity is considered a child.
     */
    @Override
    public void setGrowingAge(int age) {/* managed by DragonLifeStageHelper, so this is a no-op*/}

    @Override
    public EntitySize getSize(Pose poseIn) { return new EntitySize(BASE_WIDTH * getScale(), BASE_HEIGHT * getScale(), false); }

    /**
     * Returns the size multiplier for the current age.
     *
     * @return scale
     */
    public float getScale() { return getLifeStageController().getScale(); }

    public boolean isHatchling() { return getLifeStageController().isHatchling(); }

    public boolean isJuvenile() { return getLifeStageController().isJuvenile(); }

    public boolean isAdult() { return getLifeStageController().isAdult(); }

    @Override
    public boolean isChild() { return !isAdult(); }

    /**
     * Checks if this entity is running on a client.
     * <p>
     * Required since MCP's isClientWorld returns the exact opposite...
     *
     * @return true if the entity runs on a client or false if it runs on a server
     */
    public final boolean isClient()
    {
        return world.isRemote;
    }

    /**
     * Checks if this entity is running on a server.
     *
     * @return true if the entity runs on a server or false if it runs on a client
     */
    public final boolean isServer()
    {
        return !world.isRemote;
    }
}
