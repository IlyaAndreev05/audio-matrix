package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.AudioPort;
import io.github.ilyaandreev05.audiomatrix.graph.GraphConnection;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import javafx.scene.layout.VBox;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Graph editing commands of the shell.
 *
 * <p>Every command is implemented by a focused helper of the same package: the node inspector,
 * the route cards, the route lifecycle, the node mutations, the quick control pins and the
 * subgraph commands.</p>
 */
abstract class AppShellUiPart06 extends AppShellUiPart05 {
    protected AppShellUiPart06(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    @Override
    protected VBox graphNodeCard(AudioGraph graph) {
        return GraphNodeInspectorPanel.create(this, graph);
    }

    @Override
    protected void createGraphSubgraph(AudioGraph graph) {
        GraphSubgraphCommands.create(this, graph);
    }

    @Override
    protected void setSubgraphCollapsed(String routeId, String subgraphId, boolean collapsed) {
        GraphSubgraphCommands.setCollapsed(this, routeId, subgraphId, collapsed);
    }

    @Override
    protected void deleteGraphSubgraph(String routeId, String subgraphId) {
        GraphSubgraphCommands.delete(this, routeId, subgraphId);
    }

    @Override
    protected VBox graphConnectionCard(AudioGraph graph) {
        return GraphRoutePanels.connectionCard(this, graph);
    }

    @Override
    protected VBox graphRouteGroupCard(AudioGraph graph, Map<String, Object> config) {
        return GraphRoutePanels.routeGroupCard(this, graph, config);
    }

    @Override
    protected VBox graphSavedCopiesCard(Map<String, Object> config) {
        return GraphRoutePanels.savedCopiesCard(this, config);
    }

    @Override
    protected void createGraphRoute() {
        GraphRouteCommands.createRoute(this);
    }

    @Override
    protected void duplicateSelectedGraphRoute() {
        GraphRouteCommands.duplicateRoute(this);
    }

    @Override
    protected void renameSelectedGraphRoute() {
        GraphRouteCommands.renameRoute(this);
    }

    @Override
    protected void deleteSelectedGraphRoute() {
        GraphRouteCommands.deleteRoute(this);
    }

    @Override
    protected void activateSelectedGraphRoute() {
        GraphRouteCommands.activateRoute(this);
    }

    @Override
    protected void saveSelectedGraphCopy() {
        GraphRouteCommands.saveCopy(this);
    }

    @Override
    protected void addGraphNode(AudioGraph graph, String type) {
        GraphNodeCommands.addNode(this, graph, type);
    }

    @Override
    protected void setRouterLink(String routeId, String nodeId, String link, boolean enabled, boolean multiple) {
        GraphNodeCommands.setRouterLink(this, routeId, nodeId, link, enabled, multiple);
    }

    @Override
    protected void addRouterPort(String routeId, GraphNode node, String direction) {
        GraphNodeCommands.addRouterPort(this, routeId, node, direction);
    }

    @Override
    protected String quickControlParameter(GraphNode node) {
        return GraphQuickControlPinning.parameter(this, node);
    }

    @Override
    protected boolean quickControlPinned(String routeId, String nodeId, String parameter) {
        return GraphQuickControlPinning.pinned(this, routeId, nodeId, parameter);
    }

    @Override
    protected void toggleGraphQuickControl(String routeId, GraphNode node, String parameter, boolean pinned) {
        GraphQuickControlPinning.toggle(this, routeId, node, parameter, pinned);
    }

    @Override
    protected void updateGraph(String routeId, Consumer<List<GraphNode>> nodeChange) {
        GraphNodeCommands.updateGraph(this, routeId, nodeChange);
    }

    @Override
    protected void saveGraph(String routeId, AudioGraph graph) {
        GraphNodeCommands.saveGraph(this, routeId, graph);
    }

    @Override
    protected void updateGraphNodeParameters(String routeId, String nodeId, Consumer<Map<String, Object>> change) {
        GraphNodeCommands.updateNodeParameters(this, routeId, nodeId, change);
    }

    @Override
    protected void removeGraphNode(String routeId, String nodeId) {
        GraphNodeCommands.removeNode(this, routeId, nodeId);
    }

    @Override
    protected void connectGraphNodes(String routeId, GraphNode source, AudioPort sourcePort, GraphNode target, AudioPort targetPort) {
        GraphNodeCommands.connectNodes(this, routeId, source, sourcePort, target, targetPort);
    }

    @Override
    protected void setGraphConnectionEnabled(String routeId, String connectionId, boolean enabled) {
        GraphNodeCommands.setConnectionEnabled(this, routeId, connectionId, enabled);
    }

    @Override
    protected void removeGraphConnection(String routeId, String connectionId) {
        GraphNodeCommands.removeConnection(this, routeId, connectionId);
    }

    @Override
    protected void mutateGraphConnection(String routeId, String connectionId, java.util.function.Function<GraphConnection, GraphConnection> mutate) {
        GraphNodeCommands.mutateConnection(this, routeId, connectionId, mutate);
    }

    @Override
    protected void createGraphGroup() {
        GraphRouteCommands.createGroup(this);
    }

    @Override
    protected void renameGraphGroup(String groupId) {
        GraphRouteCommands.renameGroup(this, groupId);
    }
}
