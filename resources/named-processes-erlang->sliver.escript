#!/usr/bin/env escript
%% -*- erlang -*-
%%! -name bar@127.0.0.1 -cookie monster

main(_) ->
    net_kernel:connect_node('foo@127.0.0.1'),

    register(shell, self()),

    spawn(fun() ->
        Self = self(),
        register(ping, Self),

        {pong, 'foo@127.0.0.1'} ! ping,

        receive pong ->
            shell ! done
        end
    end),

    receive _ -> ok
    end.
