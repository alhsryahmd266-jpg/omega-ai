#!/data/data/com.termux/files/usr/bin/bash
# GVR-Agent Setup for Termux
echo "=== GVR-Agent Setup ==="

# Update packages
pkg update -y && pkg upgrade -y

# Install dependencies
pkg install -y python python-pip clang cmake make git wget curl \
               libandroid-spawn termux-api

# Install Python packages
pip install --upgrade pip
pip install llama-cpp-python requests duckduckgo-search rich

# Create working directory
mkdir -p ~/gvr-agent
cd ~/gvr-agent

# Download agent script
wget -O agent.py https://raw.githubusercontent.com/alhsryahmd266-jpg/omega-ai/main/phone_agent/agent.py

# Download model (DeepSeek-R1-Distill-Qwen-14B Q4_K_M ~8.4GB)
echo ""
echo "=== Downloading Model (8.4GB) ==="
wget -c "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-14B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-14B-Q4_K_M.gguf" \
     -O model.gguf

echo ""
echo "=== Setup Complete ==="
echo "Run with: python ~/gvr-agent/agent.py"
