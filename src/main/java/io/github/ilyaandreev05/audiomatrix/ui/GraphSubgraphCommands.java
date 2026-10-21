package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;

/** Commands that create, collapse and delete subgraphs of a route graph. */
final class GraphSubgraphCommands {
    private GraphSubgraphCommands() {}

    static void create(AppShellUiPart06 shell,AudioGraph graph){
        GraphSubgraphCreation.show(graph,shell.selectedSubgraphNodeIds,shell::styleDialog,shell::t,shell::updateRouteConfig);
    }

    static void setCollapsed(AppShellUiPart06 shell,String routeId,String subgraphId,boolean collapsed){
        GraphSubgraphUi.setCollapsed(routeId,subgraphId,collapsed,shell::t,shell::updateRouteConfig);
    }

    static void delete(AppShellUiPart06 shell,String routeId,String subgraphId){
        GraphSubgraphUi.delete(routeId,subgraphId,shell::t,shell::updateRouteConfig);
    }
}
