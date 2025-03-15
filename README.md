# DistributedSysP3

# TODO's:
    1. Implement disconnect, reconnect, multicast send. I added my personal notes to every method to help, not necessary to implement same way as I said, I just added it to help just incase.
    2. Future improvements could be to handle command sequence correctness. I.e you cant multicaset send if youre not registered, you cant reconnect if you are currently connected, etc. But right now, lets assume the sequence of commands are all valid.


## Discoonnect Extra Notes:
    - Similar to deregister, we need to:
        - set connected to false
        - close the messageSocket so it doesn't receive any future messages until it reconnects.
        - Close any data streams (i.e messageDataIn, messageDataOut)
    However, with disconnect we are not removiing the mesageLogsFile on the participant side. I believe this is the only clear distinction between disconnect and deregister. When you deregister you lose any old messages, but when you disconnect you dont.


## Reconnect Extra Notes:
    - This is also similar to register client. we need to:
        - Create serverSocket on coordinator side and away a new socket for the messageSocket.
        - Set connected to True
    However, the distinctions between reconncet and register are:
        - When you reconnect, you need to make sure youre caught up on all valid old messages.

## Multicast Send Extra Notes:
    - This is pretty straightforward, just iterate thorugh every participant in the clientMap and send them the message through the message socket.