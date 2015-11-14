#!/usr/bin/env escript
%% -*- erlang -*-
%%! -name bar@127.0.0.1 -setcookie monster

main(_) ->
    register(shell, self()),

    Pid = spawn(fun() ->
        receive _ ->
            erlang:display("Received a thing"),
            {ping, 'foo@127.0.0.1'} ! pong,
            shell ! done
        end
    end),

    register(pong, Pid),

    receive _ -> ok
    end.
