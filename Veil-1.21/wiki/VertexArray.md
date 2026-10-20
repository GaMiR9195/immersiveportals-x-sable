Veil adds a better version of `VertexBuffer` from Minecraft called `VertexArray`. They can be created with `VertexArray#create` and destroyed with `VertexArray#free`.

## Uploading Vertex Data
Vertex arrays support uploading vertex data from `BufferBuilder#end` by calling `VertexArray#upload(MeshData, DrawUsage)`. However, unlike Minecraft vertex arrays can have multiple meshes uploaded at different locations by specifying the optional `attributeStart` parameter.

#### Example
```java
// This initializes the data  
BufferBuilder builder = RenderSystem.renderThreadTesselator().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);  
// Build mesh  
builder.addVertex(0, 0, 0).setColor(-1);  
builder.addVertex(0, 1, 0).setColor(-1);  
builder.addVertex(1, 1, 0).setColor(-1);  
builder.addVertex(1, 0, 0).setColor(-1);  
  
VertexArray vertexArray = VertexArray.create();  
vertexArray.upload(builder.buildOrThrow(), VertexArray.DrawUsage.STATIC);  
  
// This goes inside a render loop  
{  
    // Make sure to bind  
    vertexArray.bind();  
    // Draw data  
    vertexArray.drawWithRenderType(RenderType.debugQuads());  
}  
  
// When not being used anymore, make sure to free it  
vertexArray.free();
```

## Using Custom Vertex Buffers
Vertex arrays can reference any OpenGL buffers, but they also have the ability to "own" their own buffers. This is convenient if a buffer is only used once for the vertex array that owns it.

## Defining Custom Vertex Attributes
Vertex attributes can be defined by calling `VertexArray#editFormat` and building the new format. Vanilla Minecraft `VertexFormat` attributes can also be applied at arbitrary locations using `VertexArrayBuilder#applyFrom(bufferIndex, buffer, attributeStart, format)`. 

- `buffer` should contain the vertex data the format specifies.
- `attributeStart` allows better control over how the format applies.
- `bufferIndex` is an arbitrary index used to bind the vertex buffer to the vertex array.

#### Full Example
```java
    VertexArray vertexArray = VertexArray.create();  
  
    // Arbitrary OpenGl buffers can be created  
    int defaultVbo = vertexArray.getOrCreateBuffer(VertexArray.VERTEX_BUFFER);  
    int extraVbo = vertexArray.getOrCreateBuffer(2);  
    int vanillaVbo = vertexArray.getOrCreateBuffer(3);  
  
    try (MemoryStack stack = MemoryStack.stackPush()) {  
        ByteBuffer vertexData = stack.malloc(Float.BYTES * 5 * 4);  
  
        // Put data in the buffer  
        vertexData.asFloatBuffer().put(new float[]{  
                0.0F, 0.0F, 0.0F, 0.0F, 0.0F,  
                0.0F, 1.0F, 0.0F, 0.0F, 1.0F,  
                1.0F, 1.0F, 0.0F, 1.0F, 0.0F,  
                1.0F, 0.0F, 0.0F, 0.0F, 1.0F  
        });  
  
        // Upload the data into the buffer  
        VertexArray.upload(defaultVbo, vertexData, VertexArray.DrawUsage.STATIC);  
  
        // Upload some extra data to another buffer  
        ByteBuffer extraData = stack.malloc(Integer.BYTES * 4);  
        extraData.asIntBuffer().put(new int[]{0xFFFFFFFF, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF});  
        VertexArray.upload(extraVbo, extraData, VertexArray.DrawUsage.STATIC);  
  
        // Manually upload indices  
        vertexArray.uploadIndexBuffer(stack.bytes(  
                (byte) 0, (byte) 1, (byte) 2, (byte) 2, (byte) 3, (byte) 0  
        ));  
        vertexArray.setIndexCount(6, VertexArray.IndexType.BYTE);  
        vertexArray.setDrawMode(VertexFormat.Mode.TRIANGLES);  
  
        // 1.2.0+ equivalent  
        vertexArray.uploadIndexBuffer(stack.bytes(  
                (byte) 0, (byte) 1, (byte) 2, (byte) 2, (byte) 3, (byte) 0  
        ), VertexArray.IndexType.BYTE);  
    }  
  
    // Set up vertex format  
    VertexArrayBuilder builder = vertexArray.editFormat();  
  
    // 5 floats per vertex  
    builder.defineVertexBuffer(0, defaultVbo, 0, Float.BYTES * 5, 0);  
    // 1 integer per vertex  
    builder.defineVertexBuffer(1, extraVbo, 0, Integer.BYTES, 0);  
  
    // Position  
    builder.setVertexAttribute(0, 0, 3, VertexArrayBuilder.DataType.FLOAT, false, 0);  
    // UV  
    builder.setVertexAttribute(1, 0, 2, VertexArrayBuilder.DataType.FLOAT, false, Float.BYTES * 3);  
    // Color  
    builder.setVertexAttribute(2, 1, 4, VertexArrayBuilder.DataType.UNSIGNED_BYTE, false, 0);  
  
    // Defines the vertex data to be pulled from the specified buffer and how the vanilla attributes should be applied  
    builder.applyFrom(3, vanillaVbo, 2, DefaultVertexFormat.PARTICLE);  
  
    vertexArray.bind();  
    vertexArray.draw();  
  
    // You can also use a render type to draw  
    vertexArray.drawWithRenderType(RenderType.solid());  
  
    // Frees the vertex array and all owned verted buffers  
    vertexArray.free();
```

## Notes
Vertex buffers can be changed at any time without having to fully re-initialize the vertex format by calling `VertexArrayBuilder#defineVertexBuffer.