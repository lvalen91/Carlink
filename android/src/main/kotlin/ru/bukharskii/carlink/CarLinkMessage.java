package ru.bukharskii.carlink;


import java.nio.ByteBuffer;
import java.nio.ByteOrder;


public class CarLinkMessage {
    public final CarLinkMessageHeader header;
    public final ByteBuffer data;

    public CarLinkMessage(CarLinkMessageHeader header, ByteBuffer data){
        this.header = header;
        this.data = data;
    }
}
