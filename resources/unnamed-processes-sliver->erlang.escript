#!/usr/bin/env escript
%% -*- erlang -*-
%%! -name bar@127.0.0.1 -setcookie monster

main(_) ->
    register(shell, self()),

    Pid = spawn(fun() ->
        register(echo, self()),
        receive {From, hai} ->
            From ! {From, hai},
            shell ! done
        end
    end),

    receive _ -> ok
    end.
