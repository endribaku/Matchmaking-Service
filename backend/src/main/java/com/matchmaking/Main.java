package com.matchmaking;

import com.matchmaking.api.ApiServer;
import com.matchmaking.service.MatchmakingService;
import com.matchmaking.service.PlayerService;

public class Main {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        PlayerService players = new PlayerService();
        MatchmakingService matchmaking = new MatchmakingService(players);
        matchmaking.start();

        ApiServer api = new ApiServer(players, matchmaking, port);
        api.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            api.stop();
            matchmaking.shutdown();
        }));

        Thread.currentThread().join();
    }
}
