package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ConfigValidator;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphProfileLibraryTest {
    @Test void creatingProfileStartsWithNoGraphRoutes() {
        Map<String,Object> config=DefaultConfig.create();
        AudioGraph route=graph("desk-route","Desk route");GraphRouteLibrary.saveRoute(config,route,"","desk");

        Map<String,Object> created=GraphProfileLibrary.createProfile(config,"desk","Empty workspace");
        String id=String.valueOf(created.get("id"));

        assertEquals("desk",config.get("active_profile"));
        assertEquals("",created.get("selected_route_id"));
        assertEquals(List.of(),created.get("active_routes"));
        assertEquals(List.of(),created.get("active_buses"));
        assertTrue(GraphRouteLibrary.routesForProfile(config,id).isEmpty());
        assertEquals(1,GraphRouteLibrary.routesForProfile(config,"desk").size());
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    @Test void profileCopyGetsIndependentGraphWorkspaceWithoutChangingTheActiveRoute() {
        Map<String,Object> config=DefaultConfig.create();
        String group=GraphRouteLibrary.createGroup(config,"desk","Desk group");
        AudioGraph route=graph("desk-route","Desk route");
        GraphRouteLibrary.saveRoute(config,route,group,"desk");
        GraphRouteLibrary.activate(config,route.id());

        Map<String,Object> duplicate=GraphProfileLibrary.duplicateProfile(config,"desk","Travel");
        String newProfile=String.valueOf(duplicate.get("id"));

        assertEquals("desk",config.get("active_profile"));
        assertEquals(route.id(),config.get("active_route"));
        assertEquals(1,GraphRouteLibrary.routesForProfile(config,"desk").size());
        assertEquals(1,GraphRouteLibrary.routesForProfile(config,newProfile).size());
        Map<String,Object> copiedRoute=GraphRouteLibrary.routesForProfile(config,newProfile).getFirst();
        assertNotEquals(route.id(),copiedRoute.get("id"));
        assertEquals(newProfile,copiedRoute.get("profile_id"));
        assertEquals(newProfile,GraphRouteLibrary.groupsForProfile(config,newProfile).getFirst().get("profile_id"));
        assertEquals(copiedRoute.get("id"),duplicate.get("selected_route_id"));
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    @Test void profileSwitchRemembersEachRoutesAndOnlyOneIsActive() {
        Map<String,Object> config=DefaultConfig.create();
        String first=GraphRouteLibrary.createGroup(config,"desk","First");
        AudioGraph deskRoute=graph("desk-route","Desk route");
        GraphRouteLibrary.saveRoute(config,deskRoute,first,"desk");
        GraphRouteLibrary.activate(config,deskRoute.id());
        Map<String,Object> travel=GraphProfileLibrary.duplicateProfile(config,"desk","Travel");
        String travelId=String.valueOf(travel.get("id"));
        String travelRoute=String.valueOf(travel.get("selected_route_id"));

        GraphRouteLibrary.activate(config,deskRoute.id());
        assertEquals(travelRoute,GraphProfileLibrary.activateProfile(config,travelId));
        assertEquals(travelId,config.get("active_profile"));
        assertEquals(travelRoute,config.get("active_route"));
        assertEquals(deskRoute.id(),GraphProfileLibrary.activateProfile(config,"desk"));
        assertEquals(deskRoute.id(),config.get("active_route"));
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    @Test void deletingActiveProfileNeedsReplacementRouteOrExplicitStopAndRemovesItsWorkspace() {
        Map<String,Object> config=DefaultConfig.create();
        AudioGraph desk=graph("desk-route","Desk route");GraphRouteLibrary.saveRoute(config,desk,"","desk");GraphRouteLibrary.activate(config,desk.id());
        Map<String,Object> duplicate=GraphProfileLibrary.duplicateProfile(config,"desk","Travel");
        String replacementId=String.valueOf(duplicate.get("id"));String replacementRoute=String.valueOf(duplicate.get("selected_route_id"));
        assertThrows(IllegalArgumentException.class,()->GraphProfileLibrary.deleteProfile(config,"desk",null,null,false));

        GraphProfileLibrary.deleteProfile(config,"desk",replacementId,replacementRoute,false);

        assertEquals(replacementId,config.get("active_profile"));
        assertEquals(replacementRoute,config.get("active_route"));
        assertTrue(GraphRouteLibrary.routesForProfile(config,"desk").isEmpty());
        assertEquals(1,GraphRouteLibrary.routesForProfile(config,replacementId).size());
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    @Test void deletingActiveProfileCanExplicitlyStopGraph() {
        Map<String,Object> config=DefaultConfig.create();
        AudioGraph desk=graph("desk-route","Desk route");GraphRouteLibrary.saveRoute(config,desk,"","desk");GraphRouteLibrary.activate(config,desk.id());
        Map<String,Object> duplicate=GraphProfileLibrary.duplicateProfile(config,"desk","Travel");
        String replacementId=String.valueOf(duplicate.get("id"));

        GraphProfileLibrary.deleteProfile(config,"desk",replacementId,"",true);

        assertEquals(replacementId,config.get("active_profile"));
        assertEquals("",config.get("active_route"));
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    private static AudioGraph graph(String id,String name) {
        List<String> channels=List.of("FL","FR");
        return new AudioGraph(id,name,List.of(new SourceNode(id+"-source","Apps","application:unassigned",channels),
                new OutputNode(id+"-output","Monitor","device:monitor",channels)),
                List.of(new GraphConnection(id+"-link",id+"-source","out",id+"-output","in",true,1)));
    }
}
