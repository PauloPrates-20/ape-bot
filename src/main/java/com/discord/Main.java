package com.discord;

import io.github.cdimascio.dotenv.Dotenv;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class Main {
    public static void main(String[] args) {        
        // Instatiates the bot and sets it's responses
        Dotenv dotenv = Dotenv.load();
        String token = dotenv.get("DISCORD_TOKEN");
        String channelId = dotenv.get("DISCORD_CHANNELID");
        String sessionId = channelId;
        String url = dotenv.get("FLOWISE_API_URL");

        DiscordClient client = DiscordClient.create(token);

        Mono<Void> login = client.withGateway((GatewayDiscordClient gateway) -> {
            // Login event
            Mono<Void> printOnLogin = gateway.on(ReadyEvent.class, event -> 
                Mono.fromRunnable(() -> {
                    final User self = event.getSelf();
                    System.out.printf("Logged in as %s\n", self.getUsername());
                })
            )
            .then();

            // Message create event
            Mono<Void> handleMessage = gateway.on(MessageCreateEvent.class, event -> {
                Message message = event.getMessage();

                if(message.getChannelId().asString().equals(channelId) && message.getAuthor().map(user -> !user.isBot()).orElse(false)) {
                    String content = message.getContent();
                    String author = message.getAuthor().map(User::getUsername).orElse("Desconhecido");
                    String question = String.format("%s envia: %s", author, content);

                    // Prepares the request for flowise
                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .headers("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(String.format("{\"question\": \"%s\", \"overrideConfig\": {\"sessionId\": \"%s\"}}", question, sessionId)))
                        .build();

                    // Gets the response
                    try {
                        HttpResponse<String> response = HttpClient.newBuilder()
                            .build()
                            .send(request, BodyHandlers.ofString());

                        String body = response.body();
                        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                        String answer = json.get("text").getAsString();

                        while (answer.length() > 0) {
                            String text = answer.substring(0, answer.length() > 2000 ? 2000 : answer.length());
                            answer = answer.substring(text.length());

                            message.getChannel()
                                .flatMap(channel -> channel.createMessage(text))
                                .subscribe();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                return Mono.empty();
            })
            .then();

            return printOnLogin.and(handleMessage);
        });

        login.block();
    }
}