import argparse
import asyncio
from pathlib import Path

import edge_tts
import soundfile as sf


PROMPTS = {
    "e00a": ("又冷又干……感觉我在南极流浪……", "-8%", "-2Hz"),
    "e00b": ("没光没水，我选择躺平。等我变成了干柴，主人记得拿我去烧火取暖……", "-8%", "-2Hz"),
    "e01": ("我掐指一算，我上辈子可能是一块海绵宝宝……但现在，我干得连蟹堡王都捏不出来了！", "-5%", "+1Hz"),
    "e02": ("又干又晒……我是不是在铁板烧上？主人！撒点孜然就能上桌了！", "+10%", "+2Hz"),
    "e03a": ("好黑呀，我是不是要长蘑菇了？", "-12%", "-2Hz"),
    "e03b": ("主人，能带我去晒晒太阳吗？我想光合作用！", "-12%", "-2Hz"),
    "e04": ("主人！我宣布！你被评为本月最佳铲屎官！奖励你摸摸我的新叶子！", "+10%", "+2Hz"),
    "e05": ("哎呀，我要被晒干了！主人给我打把伞。", "+12%", "+2Hz"),
    "e06a": ("我感觉自己像被泡发的木耳，又冷又潮，再泡下去我就要长蘑菇了！", "-12%", "-2Hz"),
    "e06b": ("又湿又暗……这不是梅雨季吗？我是不是该给自己贴个除湿袋了？我好潮啊！", "-12%", "-2Hz"),
    "e07": ("我好像泡在游泳池里了……脚脚有点闷。", "+10%", "+2Hz"),
    "e08": ("又涝又热……我觉得我的人生已经到达了火山口的顶峰！", "+12%", "+2Hz"),
}


async def generate(output_dir: Path, voice: str) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    for stem, (text, rate, pitch) in PROMPTS.items():
        mp3_path = output_dir / f"{stem}.mp3"
        wav_path = output_dir / f"{stem}.wav"
        await edge_tts.Communicate(text, voice, rate=rate, pitch=pitch).save(str(mp3_path))
        samples, sample_rate = sf.read(mp3_path, dtype="int16")
        if len(samples.shape) != 1 or sample_rate != 24000:
            raise RuntimeError(f"Unexpected audio format for {stem}: {samples.shape}, {sample_rate}")
        sf.write(wav_path, samples, sample_rate, subtype="PCM_16")
        mp3_path.unlink()


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate SmartPot offline environment prompts")
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--voice", default="zh-CN-XiaoxiaoNeural")
    args = parser.parse_args()
    asyncio.run(generate(args.output_dir, args.voice))


if __name__ == "__main__":
    main()
