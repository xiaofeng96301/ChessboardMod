package com.chessboard.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * 飞行棋棋子模型方块 —— 纯渲染用，从不放置。
 * 圆片本体按 {@link #TEAM} 换混凝土贴图，队伍一眼可辨。
 */
public class FlightPieceBlock extends Block {

    public static final EnumProperty<FlightTeam> TEAM = EnumProperty.create("team", FlightTeam.class);

    public FlightPieceBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(TEAM, FlightTeam.RED));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(TEAM);
    }
}
