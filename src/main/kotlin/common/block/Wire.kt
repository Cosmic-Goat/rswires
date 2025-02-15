package net.dblsaiko.rswires.common.block

import net.dblsaiko.hctm.common.api.BlockBundledCableIo
import net.dblsaiko.hctm.common.block.BaseWireBlock
import net.dblsaiko.hctm.common.block.BaseWireBlockEntity
import net.dblsaiko.hctm.common.block.BaseWireProperties
import net.dblsaiko.hctm.common.block.ConnectionType
import net.dblsaiko.hctm.common.block.SingleBaseWireBlock
import net.dblsaiko.hctm.common.block.WireUtils
import net.dblsaiko.hctm.common.wire.ConnectionDiscoverers
import net.dblsaiko.hctm.common.wire.ConnectionFilter
import net.dblsaiko.hctm.common.wire.NetNode
import net.dblsaiko.hctm.common.wire.Network
import net.dblsaiko.hctm.common.wire.NodeView
import net.dblsaiko.hctm.common.wire.PartExt
import net.dblsaiko.hctm.common.wire.WirePartExtType
import net.dblsaiko.hctm.common.wire.find
import net.dblsaiko.hctm.common.wire.getWireNetworkState
import net.dblsaiko.rswires.RSWires
import net.dblsaiko.rswires.common.init.BlockEntityTypes
import net.minecraft.block.AbstractBlock
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.nbt.ByteTag
import net.minecraft.nbt.Tag
import net.minecraft.server.world.ServerWorld
import net.minecraft.state.StateManager.Builder
import net.minecraft.state.property.Properties
import net.minecraft.util.DyeColor
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.world.BlockView
import net.minecraft.world.World
import net.minecraft.world.dimension.DimensionType
import java.util.*
import kotlin.experimental.and
import kotlin.experimental.or
import net.dblsaiko.rswires.common.init.Blocks as RSWiresBlocks

abstract class BaseRedstoneWireBlock(settings: AbstractBlock.Settings, height: Float) : SingleBaseWireBlock(settings, height) {

  init {
    defaultState = defaultState.with(WireProperties.POWERED, false)
  }

  private fun isCorrectBlock(state: BlockState) = state.block == RSWiresBlocks.RED_ALLOY_WIRE

  override fun appendProperties(b: Builder<Block, BlockState>) {
    super.appendProperties(b)
    b.add(WireProperties.POWERED)
  }

  override fun emitsRedstonePower(state: BlockState?): Boolean {
    return true
  }

  override fun neighborUpdate(state: BlockState, world: World, pos: BlockPos, block: Block, neighborPos: BlockPos, moved: Boolean) {
    if (world is ServerWorld) {
      WireUtils.updateClient(world, pos) // redstone connections
      RSWires.wiresGivePower = false
      if (isReceivingPower(state, world, pos) != state[WireProperties.POWERED]) {
        RedstoneWireUtils.scheduleUpdate(world, pos)
      }
      RSWires.wiresGivePower = true
    }
  }

  override fun mustConnectInternally() = true

  abstract fun isReceivingPower(state: BlockState, world: World, pos: BlockPos): Boolean

  override fun overrideConnection(world: World, pos: BlockPos, state: BlockState, side: Direction, edge: Direction, current: ConnectionType?): ConnectionType? {
    if (current == null) {
      val blockState = world.getBlockState(pos.offset(edge))
      if (blockState.block !is BaseWireBlock && blockState.emitsRedstonePower()) {
        return ConnectionType.EXTERNAL
      }
    }
    return super.overrideConnection(world, pos, state, side, edge, current)
  }

}

class RedAlloyWireBlock(settings: AbstractBlock.Settings) : BaseRedstoneWireBlock(settings, 2 / 16f) {

  override fun getStrongRedstonePower(state: BlockState, view: BlockView, pos: BlockPos, facing: Direction): Int {
    // Fix for comparator side input which only respects strong power
    if (view.getBlockState(pos.offset(facing.opposite)).block == Blocks.COMPARATOR) {
      return state.getWeakRedstonePower(view, pos, facing)
    }

    return if (
      RSWires.wiresGivePower &&
      state[WireProperties.POWERED] &&
      state[BaseWireProperties.PLACED_WIRES[facing.opposite]]
    ) 15 else 0
  }

  override fun getWeakRedstonePower(state: BlockState, view: BlockView, pos: BlockPos, facing: Direction): Int {
    return if (
      RSWires.wiresGivePower &&
      state[WireProperties.POWERED] &&
      (BaseWireProperties.PLACED_WIRES - facing.opposite).any { state[it.value] }
    ) 15 else 0
  }

  override fun createPartExtFromSide(side: Direction) = RedAlloyWirePartExt(side)

  override fun createBlockEntity(view: BlockView) = BaseWireBlockEntity(BlockEntityTypes.RED_ALLOY_WIRE)

  override fun isReceivingPower(state: BlockState, world: World, pos: BlockPos) =
    RedstoneWireUtils.isReceivingPower(state, world, pos, true)

}

class InsulatedWireBlock(settings: AbstractBlock.Settings, val color: DyeColor) : BaseRedstoneWireBlock(settings, 3 / 16f) {

  override fun createPartExtFromSide(side: Direction) = InsulatedWirePartExt(side, color)

  override fun createBlockEntity(view: BlockView) = BaseWireBlockEntity(BlockEntityTypes.INSULATED_WIRE)

  override fun getStrongRedstonePower(state: BlockState, view: BlockView, pos: BlockPos, facing: Direction): Int {
    // Fix for comparator side input which only respects strong power
    if (view.getBlockState(pos.offset(facing.opposite)).block == Blocks.COMPARATOR) {
      return state.getWeakRedstonePower(view, pos, facing)
    }

    return 0
  }

  override fun getWeakRedstonePower(state: BlockState, view: BlockView, pos: BlockPos, facing: Direction): Int {
    return if (
      RSWires.wiresGivePower &&
      state[WireProperties.POWERED] &&
      (BaseWireProperties.PLACED_WIRES - facing.opposite).any { state[it.value] }
    ) 15 else 0
  }

  override fun isReceivingPower(state: BlockState, world: World, pos: BlockPos) =
    RedstoneWireUtils.isReceivingPower(state, world, pos, false)

}

class BundledCableBlock(settings: AbstractBlock.Settings, val color: DyeColor?) : BaseWireBlock(settings, 4 / 16f) {

  override fun createExtFromTag(tag: Tag): PartExt? {
    val data = (tag as? ByteTag)?.byte ?: return null
    val inner = DyeColor.byId(data.toInt() shr 4 and 15)
    val dir = data and 15
    return if (dir in 0 until 6) BundledCablePartExt(Direction.byId(dir.toInt()), color, inner)
    else null
  }

  override fun createPartExtsFromSide(side: Direction): Set<PartExt> {
    return DyeColor.values().map { BundledCablePartExt(side, color, it) }.toSet()
  }

  override fun neighborUpdate(state: BlockState, world: World, pos: BlockPos, block: Block, neighborPos: BlockPos, moved: Boolean) {
    if (world is ServerWorld) {
      WireUtils.updateClient(world, pos) // redstone connections
      RedstoneWireUtils.scheduleUpdate(world, pos)
    }
  }

  override fun overrideConnection(world: World, pos: BlockPos, state: BlockState, side: Direction, edge: Direction, current: ConnectionType?): ConnectionType? {
    if (current == null) {
      val blockState = world.getBlockState(pos.offset(edge))
      val block = blockState.block
      if (block !is BaseWireBlock && block is BlockBundledCableIo && block.canBundledConnectTo(blockState, world, pos.offset(edge), edge, side)) {
        return ConnectionType.EXTERNAL
      }
    }
    return super.overrideConnection(world, pos, state, side, edge, current)
  }

  override fun createBlockEntity(view: BlockView) = BaseWireBlockEntity(BlockEntityTypes.BUNDLED_CABLE)

}

data class RedAlloyWirePartExt(override val side: Direction) : PartExt, WirePartExtType, PartRedstoneCarrier {
  override val type = RedstoneWireType.RedAlloy

  private fun isCorrectBlock(state: BlockState) = state.block == RSWiresBlocks.RED_ALLOY_WIRE

  override fun getState(world: World, self: NetNode): Boolean {
    val pos = self.data.pos
    val state = world.getBlockState(pos)
    return isCorrectBlock(state) && state[WireProperties.POWERED]
  }

  override fun setState(world: World, self: NetNode, state: Boolean) {
    val pos = self.data.pos
    var blockState = world.getBlockState(pos)

    if (!isCorrectBlock(blockState)) return

    blockState = blockState.with(WireProperties.POWERED, state)
    world.setBlockState(pos, blockState)

    // update neighbors 2 blocks away for strong redstone signal
    WireUtils.getOccupiedSides(blockState)
      .map { pos.offset(it) }
      .flatMap { Direction.values().map { d -> it.offset(d) } }
      .distinct()
      .minus(pos)
      .forEach { world.updateNeighbor(it, blockState.block, pos) }
  }

  override fun getInput(world: World, self: NetNode): Boolean {
    val pos = self.data.pos
    return RedstoneWireUtils.isReceivingPower(world.getBlockState(pos), world, pos, true)
  }

  override fun tryConnect(self: NetNode, world: ServerWorld, pos: BlockPos, nv: NodeView): Set<NetNode> {
    return find(ConnectionDiscoverers.WIRE, RedstoneCarrierFilter, self, world, pos, nv)
  }

  override fun onChanged(self: NetNode, world: ServerWorld, pos: BlockPos) {
    RedstoneWireUtils.scheduleUpdate(world, pos)
    WireUtils.updateClient(world, pos)
  }

  override fun toTag(): Tag {
    return ByteTag.of(side.id.toByte())
  }
}

data class InsulatedWirePartExt(override val side: Direction, val color: DyeColor) : PartExt, WirePartExtType, PartRedstoneCarrier {
  override val type = RedstoneWireType.Colored(color)

  private fun isCorrectBlock(state: BlockState) = state.block in RSWiresBlocks.INSULATED_WIRES.values

  override fun getState(world: World, self: NetNode): Boolean {
    val pos = self.data.pos
    val state = world.getBlockState(pos)
    return isCorrectBlock(state) && state[WireProperties.POWERED]
  }

  override fun setState(world: World, self: NetNode, state: Boolean) {
    val pos = self.data.pos
    val blockState = world.getBlockState(pos)
    if (!isCorrectBlock(blockState)) return
    world.setBlockState(pos, blockState.with(WireProperties.POWERED, state))
  }

  override fun getInput(world: World, self: NetNode): Boolean {
    val pos = self.data.pos
    val state = world.getBlockState(pos)

    if (!isCorrectBlock(state)) return false

    return RedstoneWireUtils.isReceivingPower(state, world, pos, false)
  }

  override fun tryConnect(self: NetNode, world: ServerWorld, pos: BlockPos, nv: NodeView): Set<NetNode> {
    return find(ConnectionDiscoverers.WIRE, RedstoneCarrierFilter, self, world, pos, nv)
  }

  override fun onChanged(self: NetNode, world: ServerWorld, pos: BlockPos) {
    RedstoneWireUtils.scheduleUpdate(world, pos)
    WireUtils.updateClient(world, pos)
  }

  override fun toTag(): Tag {
    return ByteTag.of(side.id.toByte())
  }
}

data class BundledCablePartExt(override val side: Direction, val color: DyeColor?, val inner: DyeColor) : PartExt, WirePartExtType, PartRedstoneCarrier {
  override val type = RedstoneWireType.Bundled(color, inner)

  fun isCorrectBlock(state: BlockState) = state.block == RSWiresBlocks.UNCOLORED_BUNDLED_CABLE ||
    state.block in RSWiresBlocks.COLORED_BUNDLED_CABLES.values

  override fun getState(world: World, self: NetNode): Boolean {
    return false
  }

  override fun setState(world: World, self: NetNode, state: Boolean) {}

  override fun getInput(world: World, self: NetNode): Boolean {
    val pos = self.data.pos
    val state = world.getBlockState(pos)

    if (!isCorrectBlock(state)) return false

    return BundledCableUtils.getReceivedData(state, world, pos).toUInt() and (1u shl inner.id) != 0u
  }

  override fun tryConnect(self: NetNode, world: ServerWorld, pos: BlockPos, nv: NodeView): Set<NetNode> {
    return find(ConnectionDiscoverers.WIRE, RedstoneCarrierFilter, self, world, pos, nv)
  }

  override fun onChanged(self: NetNode, world: ServerWorld, pos: BlockPos) {
    RedstoneWireUtils.scheduleUpdate(world, pos)
    WireUtils.updateClient(world, pos)
  }

  override fun toTag(): Tag {
    return ByteTag.of(side.id.toByte() or (inner.id shl 4).toByte())
  }
}

interface PartRedstoneCarrier : PartExt {
  val type: RedstoneWireType

  fun getState(world: World, self: NetNode): Boolean

  fun setState(world: World, self: NetNode, state: Boolean)

  fun getInput(world: World, self: NetNode): Boolean
}

sealed class RedstoneWireType {
  object RedAlloy : RedstoneWireType()
  data class Colored(val color: DyeColor) : RedstoneWireType()
  data class Bundled(val color: DyeColor?, val inner: DyeColor) : RedstoneWireType()

  fun canConnect(other: RedstoneWireType): Boolean {
    if (this == other) return true
    if (this == RedAlloy && other is Colored || this is Colored && other == RedAlloy) return true
    if (this is Colored && other is Bundled && other.inner == this.color || this is Bundled && other is Colored && this.inner == other.color) return true
    if (other is Bundled && this == Bundled(null, other.inner) || this is Bundled && other == Bundled(null, this.inner)) return true
    return false
  }
}

object RedstoneCarrierFilter : ConnectionFilter {
  override fun accepts(self: NetNode, other: NetNode): Boolean {
    val d1 = self.data.ext as? PartRedstoneCarrier ?: return false
    val d2 = other.data.ext as? PartRedstoneCarrier ?: return false
    return d1.type.canConnect(d2.type)
  }
}

object WireProperties {
  val POWERED = Properties.POWERED
}

object RedstoneWireUtils {

  var scheduled = mapOf<DimensionType, Set<UUID>>()

  fun scheduleUpdate(world: ServerWorld, pos: BlockPos) {
    scheduled += world.dimension to scheduled[world.dimension].orEmpty() + world.getWireNetworkState().controller.getNetworksAt(pos).map { it.id }
  }

  fun flushUpdates(world: ServerWorld) {
    val wireNetworkState = world.getWireNetworkState()
    for (id in scheduled[world.dimension].orEmpty()) {
      val net = wireNetworkState.controller.getNetwork(id)
      if (net != null) updateState(world, net)
    }
    scheduled -= world.dimension
  }

  fun updateState(world: World, network: Network) {
    val isOn = try {
      RSWires.wiresGivePower = false
      network.getNodes().any { (it.data.ext as PartRedstoneCarrier).getInput(world, it) }
    } finally {
      RSWires.wiresGivePower = true
    }
    for (node in network.getNodes()) {
      val ext = node.data.ext as PartRedstoneCarrier
      ext.setState(world, node, isOn)
    }
  }

  fun isReceivingPower(state: BlockState, world: World, pos: BlockPos, receiveFromBottom: Boolean): Boolean {
    val sides = WireUtils.getOccupiedSides(state)
    val weakSides = Direction.values().filter { a -> sides.any { b -> b.axis != a.axis } }.distinct() - sides
    return weakSides
             .map {
               val otherPos = pos.offset(it)
               if (world.getBlockState(otherPos).block == Blocks.REDSTONE_WIRE) 0
               else {
                 val state = world.getBlockState(otherPos)
                 if (state.isSolidBlock(world, otherPos)) state.getStrongRedstonePower(world, otherPos, it)
                 else state.getWeakRedstonePower(world, otherPos, it)
               }
             }
             .any { it > 0 } ||
           (receiveFromBottom && sides
             .filterNot { world.getBlockState(pos.offset(it)).block == Blocks.REDSTONE_WIRE }
             .any { world.getEmittedRedstonePower(pos.offset(it), it) > 0 })
  }

}

object BundledCableUtils {

  fun getReceivedData(state: BlockState, world: World, pos: BlockPos): UShort {
    val sides = WireUtils.getOccupiedSides(state)
    val inputSides = Direction.values().filter { a -> sides.any { b -> b.axis != a.axis } }.distinct() - sides
    return inputSides
      .flatMap { side ->
        val edges = (Direction.values().toSet() - sides).filter { edge -> edge.axis != side.axis }
        edges.map { edge ->
          val otherPos = pos.offset(side)
          if (world.getBlockState(otherPos).block == RSWiresBlocks.UNCOLORED_BUNDLED_CABLE) 0u
          else {
            val state = world.getBlockState(otherPos)
            val block = state.block
            if (block is BlockBundledCableIo) {
              block.getBundledOutput(state, world, otherPos, side.opposite, edge)
            } else 0u
          }
        }
      }
      .fold(0u.toUShort()) { a, b -> a or b }
  }

}